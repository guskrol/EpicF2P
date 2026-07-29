package org.example.core.items;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.ItemDetail;

public final class GePricing {
    private static final double QUICK_BUY_MARKUP = 1.15D;
    private static final double QUICK_SELL_MARKDOWN = 0.85D;

    private GePricing() {
    }

    public static int quickBuyPrice(APIContext ctx, String itemName) {
        return quickBuyPrice(ctx, itemName, 0L);
    }

    public static int quickBuyPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        int fixed = F2PItemRegistry.buyPrice(itemName);
        long market = marketBuyReference(ctx, itemName);
        long price = market > 0L ? buyPremium(market) : 1L;

        price = Math.max(price, fixed);
        price = Math.max(price, fallbackBasePrice);
        return clampPrice(price);
    }

    public static int exchangeQuickBuyPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        long market = marketBuyReference(ctx, itemName);
        long price = market > 0L ? buyPremium(market) : Math.max(1L, fallbackBasePrice);
        return clampPrice(price);
    }

    public static int quickSellPrice(APIContext ctx, String itemName) {
        return quickSellPrice(ctx, itemName, 0L);
    }

    public static int quickSellPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        int fixed = F2PItemRegistry.sellPrice(itemName);
        long market = marketSellReference(ctx, itemName);

        if (market > 0L) {
            long price = sellDiscount(market);
            if (fixed > 0) {
                price = Math.min(price, fixed);
            }
            return clampPrice(price);
        }

        if (fixed > 0) {
            return fixed;
        }
        if (fallbackBasePrice > 0L) {
            return clampPrice(sellDiscount(fallbackBasePrice));
        }
        return 1;
    }

    public static int exchangeQuickSellPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        long market = marketSellReference(ctx, itemName);

        if (market > 0L) {
            return clampPrice(sellDiscount(market));
        }
        if (fallbackBasePrice > 0L) {
            return clampPrice(sellDiscount(fallbackBasePrice));
        }
        return 1;
    }

    private static long marketBuyReference(APIContext ctx, String itemName) {
        long wiki = wikiBuyReference(itemName);
        if (wiki > 0L) {
            return wiki;
        }

        ItemDetail detail = itemDetail(ctx, itemName);
        if (detail == null) {
            return 0L;
        }

        int price = firstPositive(detail.getHighestPrice(), detail.getLowestPrice());
        if (price > 0 && detail.isEquipable() && detail.getHighAlch() > 0) {
            price = (int) Math.min(price, (long) detail.getHighAlch() * 3L);
        }
        return price;
    }

    private static long marketSellReference(APIContext ctx, String itemName) {
        long wiki = wikiSellReference(itemName);
        if (wiki > 0L) {
            return wiki;
        }

        ItemDetail detail = itemDetail(ctx, itemName);
        if (detail == null) {
            return 0L;
        }

        int price = firstPositive(detail.getLowestPrice(), detail.getHighestPrice());
        if (price > 0 && detail.isEquipable() && detail.getHighAlch() > 0) {
            price = (int) Math.min(price, (long) detail.getHighAlch() * 3L);
        }
        return price;
    }

    private static long wikiBuyReference(String itemName) {
        WikiPriceClient.Price price = WikiPriceClient.latest(itemName);
        return price == null ? 0L : price.buyReference();
    }

    private static long wikiSellReference(String itemName) {
        WikiPriceClient.Price price = WikiPriceClient.latest(itemName);
        return price == null ? 0L : price.sellReference();
    }

    private static ItemDetail itemDetail(APIContext ctx, String itemName) {
        if (ctx == null || itemName == null || itemName.isBlank()) {
            return null;
        }

        try {
            return ctx.pricing().get(itemName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int firstPositive(int preferred, int fallback) {
        return preferred > 0 ? preferred : fallback;
    }

    private static long buyPremium(long price) {
        long markedUp = (long) Math.ceil(price * QUICK_BUY_MARKUP);
        long oneTickAbove = price + Math.max(1L, Math.min(5L, price / 100L));
        return Math.max(markedUp, oneTickAbove);
    }

    private static long sellDiscount(long price) {
        return Math.max(1L, (long) Math.floor(price * QUICK_SELL_MARKDOWN));
    }

    private static int clampPrice(long price) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, price));
    }
}
