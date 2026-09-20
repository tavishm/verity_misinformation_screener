package org.fairc.forwardcheck;

import android.content.res.AssetManager;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Apache-2.0 pretrained multilingual static WordPiece embedding with an int8 table. */
final class TinyEmbedding {
    private final int maxTokens;
    private final float[] tokenScores;
    private final Map<String,Integer> vocab;
    private final double[] head;
    private final double intercept;

    TinyEmbedding(AssetManager assets, double[] head, double intercept, int maxTokens) throws IOException {
        this(assets.open("forward_embedding_projection.bin"), assets.open("forward_embedding_vocab.txt"), head, intercept, maxTokens);
    }

    TinyEmbedding(InputStream embeddingStream, InputStream vocabularyStream,
                  double[] head, double intercept, int maxTokens) throws IOException {
        this.head=head; this.intercept=intercept; this.maxTokens=maxTokens;
        int count;
        try (DataInputStream b=new DataInputStream(new BufferedInputStream(embeddingStream))) {
            if (b.readByte()!='F'||b.readByte()!='C'||b.readByte()!='S'||b.readByte()!='P'
                    ||Integer.reverseBytes(b.readInt())!=1) throw new IOException("Bad embedding header");
            count=Integer.reverseBytes(b.readInt()); tokenScores=new float[count];
            for(int i=0;i<count;i++) tokenScores[i]=Float.intBitsToFloat(Integer.reverseBytes(b.readInt()));
        }
        vocab=new HashMap<>(count*4/3);
        String line; int id=0;
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(vocabularyStream,StandardCharsets.UTF_8))) {
            while((line=reader.readLine())!=null) vocab.put(line,id++);
        }
        if(id!=count) throw new IOException("Embedding/vocabulary size mismatch");
    }

    double score(String text) {
        int[] ids=tokenize(text);
        double value=intercept;
        double total=0;
        for(int id:ids) total += tokenScores[id];
        value += total/ids.length;
        value=Math.max(-35,Math.min(35,value));
        return 1.0/(1.0+Math.exp(-value));
    }

    int[] tokenize(String text) {
        String normalized=bertNormalize(LocalClassifier.limitCodePoints(text));
        List<Integer> ids=new ArrayList<>(); ids.add(101);
        for(String token:preTokens(normalized)) {
            int chars=token.codePointCount(0,token.length());
            if(chars>100) { ids.add(100); continue; }
            int wordStart=ids.size(), start=0; boolean bad=false;
            while(start<token.length()) {
                int end=token.length(), found=-1, foundEnd=-1;
                while(end>start) {
                    String piece=(start==0?"":"##")+token.substring(start,end);
                    Integer candidate=vocab.get(piece);
                    if(candidate!=null) { found=candidate; foundEnd=end; break; }
                    end=token.offsetByCodePoints(end,-1);
                }
                if(found<0) { bad=true; break; }
                ids.add(found); start=foundEnd;
                if(ids.size()>=maxTokens-1) break;
            }
            if(bad) { while(ids.size()>wordStart) ids.remove(ids.size()-1); ids.add(100); }
            if(ids.size()>=maxTokens-1) break;
        }
        ids.add(102);
        int[] out=new int[ids.size()]; for(int i=0;i<out.length;i++) out[i]=ids.get(i); return out;
    }

    private static String bertNormalize(String text) {
        StringBuilder clean=new StringBuilder();
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i); i+=Character.charCount(cp); int type=Character.getType(cp);
            if(cp==0 || cp==0xfffd) continue;
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)) clean.append(' ');
            else if(type==Character.CONTROL || type==Character.FORMAT) continue;
            else {
                if(isChinese(cp)) clean.append(' ');
                clean.appendCodePoint(cp);
                if(isChinese(cp)) clean.append(' ');
            }
        }
        String lower=clean.toString().toLowerCase(Locale.ROOT);
        String decomposed=Normalizer.normalize(lower,Normalizer.Form.NFD);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<decomposed.length();) { int cp=decomposed.codePointAt(i); i+=Character.charCount(cp); if(Character.getType(cp)!=Character.NON_SPACING_MARK) out.appendCodePoint(cp); }
        return out.toString();
    }
    private static List<String> preTokens(String value) {
        List<String> out=new ArrayList<>(); StringBuilder current=new StringBuilder();
        for(int i=0;i<value.length();) { int cp=value.codePointAt(i); i+=Character.charCount(cp);
            int type=Character.getType(cp); boolean punctuation=(cp>=33&&cp<=47)||(cp>=58&&cp<=64)||(cp>=91&&cp<=96)||(cp>=123&&cp<=126)||type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION||type==Character.START_PUNCTUATION||type==Character.END_PUNCTUATION||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION||type==Character.OTHER_PUNCTUATION;
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||punctuation) { if(current.length()>0){out.add(current.toString());current.setLength(0);} if(punctuation)out.add(new String(Character.toChars(cp))); }
            else current.appendCodePoint(cp);
        }
        if(current.length()>0)out.add(current.toString()); return out;
    }
    private static boolean isChinese(int cp) { return (cp>=0x4E00&&cp<=0x9FFF)||(cp>=0x3400&&cp<=0x4DBF)||(cp>=0x20000&&cp<=0x2A6DF)||(cp>=0x2A700&&cp<=0x2B73F)||(cp>=0x2B740&&cp<=0x2B81F)||(cp>=0x2B820&&cp<=0x2CEAF)||(cp>=0xF900&&cp<=0xFAFF)||(cp>=0x2F800&&cp<=0x2FA1F); }
}
