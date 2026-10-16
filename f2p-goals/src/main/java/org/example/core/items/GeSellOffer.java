package org.example.core.items;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;

/** Confirms only a verified sell setup; setter return values are not offer confirmation. */
public final class GeSellOffer {
    private GeSellOffer() { }

    private static APIContext pendingContext;
    private static String pendingItem;
    private static int pendingQuantity;
    private static int pendingPrice;
    private static long priceEnteredAt;

    private static void clearPendingPrice() {
        pendingContext = null;
        pendingItem = null;
        priceEnteredAt = 0;
    }

    private static void log(APIContext ctx, String message) {
        try { if (ctx.script() != null) ctx.script().logger().info(message); }
        catch (RuntimeException ignored) { }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "").trim();
    }

    public static synchronized boolean place(APIContext ctx, String name, int quantity, int price) {
        if (quantity <= 0 || price <= 0) return false;
        IGrandExchangeAPI ge = ctx.grandExchange();
        ItemWidget item = ctx.inventory().getItem(name);
        if (item == null) return false;
        int id = item.getUnNotedId();
        if (id <= 0) id = item.getId();
        if (ge.getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER
                || (ge.getOfferItem() != id && ge.getOfferItem() != item.getId())) {
            clearPendingPrice();
            ge.backToOverview();
            ge.newSellOffer(name);
            return false;
        }
        if (ge.getOfferQuantity() != quantity) {
            clearPendingPrice();
            ge.setQuantity(quantity);
            com.epicbot.api.shared.util.time.Time.sleep(250, 450);
            if (ge.getOfferQuantity() != quantity) return false;
        }
        int actualPrice = ge.getOfferPrice();
        log(ctx, "[GE Sell] item=" + ge.getOfferItem() + " qty=" + ge.getOfferQuantity()
                + " price=" + actualPrice + " expected=" + price);
        boolean priceEntered = pendingContext == ctx && name.equals(pendingItem)
                && pendingQuantity == quantity && pendingPrice == price
                && System.currentTimeMillis() - priceEnteredAt < 30_000L;
        if (actualPrice != price && !priceEntered) {
            ge.setPrice(price);
            pendingContext = ctx;
            pendingItem = name;
            pendingQuantity = quantity;
            pendingPrice = price;
            priceEnteredAt = System.currentTimeMillis();
            actualPrice = ge.getOfferPrice();
            log(ctx, "[GE Sell] Entered price " + price + "; displayed=" + actualPrice);
            if (actualPrice != price || ge.getCurrentScreen()
                    != IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER) return false;
        }
        if (ge.getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER) return false;
        log(ctx, "[GE Sell] Confirming " + name + " at " + price + " (displayed price=" + actualPrice + ")");
        int packed = com.epicbot.api.gameval.InterfaceID.GeOffers.SETUP_CONFIRM;
        WidgetChild confirm = ctx.widgets().get(packed >>> 16, packed & 0xffff);
        if (confirm != null && confirm.isValid() && confirm.isVisible()
                && confirm.getCentralPoint() != null) {
            log(ctx, "[GE Sell] Clicking Confirm widget " + (packed >>> 16) + ":" + (packed & 0xffff));
            if (ctx.mouse().click(confirm.getCentralPoint(), false) || confirm.click(false)) {
                com.epicbot.api.shared.util.time.Time.sleep(600, 1000);
                boolean submitted = ge.getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER;
                log(ctx, "[GE Sell] Confirm clicked; screen=" + ge.getCurrentScreen());
                if (submitted) clearPendingPrice();
                return submitted;
            }
        }
        for (WidgetChild button : ctx.widgets().getAllChildren(widget ->
                widget != null && widget.isValid() && !widget.isHidden()
                        && widget.getWidth() > 0 && widget.getHeight() > 0
                        && ("Confirm".equalsIgnoreCase(clean(widget.getText()))
                            || "Confirm".equalsIgnoreCase(clean(widget.getRawText()))
                            || "Confirm offer".equalsIgnoreCase(clean(widget.getText()))
                            || widget.hasAction("Confirm")))) {
            if (button.click(false) || (button.getCentralPoint() != null
                    && ctx.mouse().click(button.getCentralPoint(), false))) {
                com.epicbot.api.shared.util.time.Time.sleep(600, 1000);
                boolean submitted = ge.getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER;
                if (submitted) clearPendingPrice();
                return submitted;
            }
        }
        boolean submitted = ge.confirmOffer();
        if (submitted) clearPendingPrice();
        return submitted;
    }
}
