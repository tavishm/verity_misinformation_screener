package org.fairc.forwardcheck;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;

/** Plain-Java parity harness; exits nonzero on tokenizer/hash drift. */
public final class LocalClassifierParityTest {
    private static final int DIMENSIONS = 16384;

    public static void main(String[] args) throws Exception {
        check("Hello!", "hello!", 16,
                "4f9e335c1edb61feaa18e29377f2001ec626c16704717fc98c920ee6e0aba14c");
        check("India reported GDP growth of 7.8 percent in 2026.",
                "india reported gdp growth of 7.8 percent in 2026.", 162,
                "a60d876d93dd88ba3f1484fdddd07759769944b8305fe3524b7abc29a3df7c05");
        check("सरकार ने 2026 में नई योजना की घोषणा की है।",
                "सरकार ने 2026 में नई योजना की घोषणा की है।", 140,
                "f5bae020bb1ae7c7db032e3837cb894696a3a07529a5881d050bab29cf086437");
        if (args.length == 1) checkEmbedding(args[0]);
        String bounded = "a".repeat(4999) + "😀";
        if (!LocalClassifier.limitCodePoints(bounded + "ignored").equals(bounded))
            throw new AssertionError("5000-code-point limit drift");
        System.out.println("LocalClassifier Java feature parity OK");
        if(args.length==0) {
            String benchmarkText = "I believe vaccines cause infertility, but I love my family. ".repeat(20);
            for (int i = 0; i < 2000; i++) LocalClassifier.featureIndices(benchmarkText, DIMENSIONS);
            long started = System.nanoTime();
            for (int i = 0; i < 10000; i++) LocalClassifier.featureIndices(benchmarkText, DIMENSIONS);
            double micros = (System.nanoTime() - started) / 10000.0 / 1000.0;
            System.out.printf("Warm JVM fallback feature extraction: %.1f us/message (workstation, not phone)%n", micros);
        }
    }

    private static void checkEmbedding(String assets) throws Exception {
        double[] head=new double[128]; // Projection asset already contains the trained head.
        TinyEmbedding model=new TinyEmbedding(
                new FileInputStream(assets+"/forward_embedding_projection.bin"),
                new FileInputStream(assets+"/forward_embedding_vocab.txt"),head,-.6762746328228199,256);
        embeddingCase(model,"hello world",new int[]{101,29155,10228,102},.0009101900163558047);
        embeddingCase(model,"नमस्ते दुनिया",new int[]{101,566,49611,11354,78932,102},.3543214585243166);
        embeddingCase(model,"The rate is 4 percent.",new int[]{101,10103,17593,10127,125,22393,119,102},.9865199375701689);
        embeddingCase(model,"Cafe\u0301\tभारत\nnews",new int[]{101,18427,14668,11636,102},.9508816632475616);
        embeddingCase(model,"$5+4=9^2|yes",new int[]{101,109,126,116,125,134,130,141,123,170,31617,102},.3352663066815609);
        embeddingCase(model,"hello🙂",new int[]{101,100,102},.09428113079914892);
        embeddingCase(model,"a".repeat(101),new int[]{101,100,102},.09428113079914892);
        int[] truncated=model.tokenize("hello ".repeat(300));
        if(truncated.length!=256 || truncated[0]!=101 || truncated[255]!=102)
            throw new AssertionError("256-token truncation drift");
        for(int i=0;i<1000;i++)model.score("Proud of my kids. Lemon water cures cancer.");
        long start=System.nanoTime();
        for(int i=0;i<5000;i++)model.score("Proud of my kids. Lemon water cures cancer.");
        System.out.printf("Warm JVM embedding gate: %.1f us/message (workstation, not phone)%n",
                (System.nanoTime()-start)/5000.0/1000.0);
    }

    private static void embeddingCase(TinyEmbedding model,String text,int[] ids,double score) {
        if(!java.util.Arrays.equals(model.tokenize(text),ids))throw new AssertionError("WordPiece parity: "+text);
        if(Math.abs(model.score(text)-score)>1e-7)throw new AssertionError("embedding score parity: "+text+" "+model.score(text));
    }

    private static void check(String text, String normalized, int count, String digest) throws Exception {
        if (!LocalClassifier.normalize(text).equals(normalized)) throw new AssertionError("normalization drift");
        Set<Integer> values = new TreeSet<>(LocalClassifier.featureIndices(text, DIMENSIONS));
        if (values.size() != count) throw new AssertionError("feature count drift: " + values.size());
        StringBuilder joined = new StringBuilder();
        for (int value : values) {
            if (joined.length() > 0) joined.append(',');
            joined.append(value);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                joined.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) hex.append(String.format("%02x", item & 0xff));
        if (!hex.toString().equals(digest)) throw new AssertionError("feature hash drift: " + hex);
    }
}
