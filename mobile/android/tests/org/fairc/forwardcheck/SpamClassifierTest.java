package org.fairc.forwardcheck;

import java.io.FileInputStream;

public final class SpamClassifierTest {
    private static TinyEmbedding model;
    private static double threshold;
    private static void check(String text, boolean expected) {
        double score=model.score(text); String reason=SpamPolicy.reason(text,score,threshold);
        if(reason.isEmpty()==expected)throw new AssertionError("Wrong routing (score="+score+"): "+text+" => "+reason);
    }
    public static void main(String[] args) throws Exception {
        model=new TinyEmbedding(new FileInputStream(args[0]+"/spam_embedding_projection.bin"),new FileInputStream(args[0]+"/forward_embedding_vocab.txt"),new double[128],Double.parseDouble(args[1]),256);
        threshold=Double.parseDouble(args[2]);
        check("Send me your OTP to verify your bank account.",true);
        check("Please share your UPI PIN with our support team.",true);
        check("अपना ओटीपी हमें भेजें",true);
        check("You won a lottery prize, pay the processing fee to claim it.",true);
        check("Your account will be blocked today. Click https://example.invalid to update KYC immediately.",true);
        check("Scan this QR code to receive your refund.",true);
        check("Congratulations! You have won a free cash prize. Claim your reward now!",true);
        check("Happy Diwali!",false);
        check("I am proud of my kids.",false);
        check("Never share your OTP with anyone.",false);
        check("Your OTP is 123456. Do not share it with anyone.",false);
        check("Warning: scammers ask you to pay a processing fee to claim a lottery prize.",false);
        check("अपना ओटीपी कभी किसी को मत बताएं",false);
        check("Hi, this is my new number. See you tomorrow.",false);
        check("Vaccines cause autism.",false);
        check("The bank announced new interest rates today.",false);
        check("I think the prize should go to our team.",false);
        check("Here is your appointment confirmation for tomorrow.",false);
        check("Never share passwords. Please send me your OTP.",true);
        String modelOnly="Exclusive mobile offer: free bonus credits for premium subscribers. Text YES to collect your reward today.";
        check(modelOnly,true);
        if(!SpamPolicy.reason(modelOnly,0,threshold).isEmpty())throw new AssertionError("model-only test accidentally matches a rule");
        check("Your bank account has been credited with your monthly salary.",false);
        // Exact compiled-score parity, checked against the Python reference scorer.
        if(args.length>3 && Math.abs(model.score("A synthetic test message.")-Double.parseDouble(args[3]))>1e-7)throw new AssertionError("projection parity");
        System.out.println("PASS SpamClassifierTest: 21 routing cases and projection parity");
    }
}
