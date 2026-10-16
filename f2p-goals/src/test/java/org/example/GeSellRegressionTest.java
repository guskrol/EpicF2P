package org.example;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.methods.*;
import org.example.core.items.GeSellOffer;
import java.lang.reflect.Proxy;

public final class GeSellRegressionTest {
    public static void main(String[] args) {
        int[] state = {0, 0, 0, 0, 0, 0};
        ItemWidget item = stub(ItemWidget.class, (p,m,a) -> switch(m.getName()) {
            case "getId", "getUnNotedId" -> 882;
            default -> null;
        });
        IInventoryAPI inventory = stub(IInventoryAPI.class, (p,m,a) -> item);
        IGrandExchangeAPI ge = stub(IGrandExchangeAPI.class, (p,m,a) -> switch(m.getName()) {
            case "getCurrentScreen" -> state[0] == 0 ? IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW
                    : IGrandExchangeAPI.GrandExchangeScreen.SETUP_SELL_OFFER;
            case "getOfferItem" -> state[1];
            case "getOfferQuantity" -> state[2];
            case "getOfferPrice" -> -1;
            case "placeSellOffer" -> false;
            case "backToOverview" -> true;
            case "newSellOffer" -> { state[0]=1; state[1]=882; yield true; }
            case "setQuantity" -> { state[2]=(Integer)a[0]; yield false; }
            case "setPrice" -> { state[5]++; yield false; }
            case "confirmOffer" -> { state[4]++; yield true; }
            default -> null;
        });
        IVariablesAPI vars = stub(IVariablesAPI.class, (p,m,a) -> state[3]);
        IWidgetsAPI widgets = stub(IWidgetsAPI.class, (p,m,a) -> m.getName().equals("get") ? null : java.util.List.of());
        APIContext ctx = stub(APIContext.class, (p,m,a) -> switch(m.getName()) {
            case "inventory" -> inventory;
            case "vars" -> vars;
            case "widgets" -> widgets;
            case "script" -> null;
            default -> ge;
        });
        if(GeSellOffer.place(ctx,"Bronze arrow",100,1)) throw new AssertionError("premature confirmation");
        if(GeSellOffer.place(ctx,"Bronze arrow",100,1) || state[4]!=0 || state[5]!=1)
            throw new AssertionError("price should be entered once before confirmation");
        if(!GeSellOffer.place(ctx,"Bronze arrow",100,1) || state[4]!=1 || state[5]!=1)
            throw new AssertionError("1 gp offer not confirmed despite stale price readings");
        state[1]=884;
        GeSellOffer.place(ctx,"Bronze arrow",100,1);
        if(state[4]!=1) throw new AssertionError("wrong item confirmed");
        System.out.println("PASS: sell confirmed after one price entry despite stale price readings; wrong item guarded");
    }
    @SuppressWarnings("unchecked")
    static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
}
