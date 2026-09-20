package org.fairc.forwardcheck.social;

import java.util.*;

/** Keep uncertain charges reserved. A timeout is not evidence of a free request. */
final class SocialBudget {
    static final double LIMIT_USD=20.0, QUICK_RESERVE=.003, NEWS_RESERVE=.012, DETAIL_RESERVE=.04;
    static String limitLabel(){return String.format(java.util.Locale.US,"$%.0f",LIMIT_USD);}
    private final Map<String,Double> charges=new HashMap<>();
    boolean canStart(double reserve){return Double.isFinite(reserve)&&reserve>0&&total()+reserve<=LIMIT_USD;}
    void reserve(String id,double amount){charges.put(id,amount);}
    void settle(String id,double cost){if(Double.isFinite(cost)&&cost>=0)charges.put(id,cost);}
    double total(){double sum=0;for(double cost:charges.values())sum+=cost;return sum;}
}
