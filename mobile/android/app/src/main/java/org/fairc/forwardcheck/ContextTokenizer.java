package org.fairc.forwardcheck;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Cased multilingual BERT WordPiece tokenizer. Hindi marks and case are preserved.
 * The exported model's tokenizer.json is the reference; parity fixtures are shipped.
 */
final class ContextTokenizer {
    private final Map<String,Integer> vocab=new HashMap<>(170000);
    private static final Pattern SPECIAL=Pattern.compile("\\[(?:CLS|SEP|PAD|UNK|MASK)\\]");
    ContextTokenizer(InputStream input)throws IOException{
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;int id=0;while((line=reader.readLine())!=null)vocab.put(line,id++);}
        if(vocab.size()!=119547)throw new IOException("Wrong contextual vocabulary");
    }
    int[] encode(String text,int max){
        List<Integer> ids=new ArrayList<>();ids.add(101);Matcher special=SPECIAL.matcher(text);int previous=0;
        while(special.find()){append(text.substring(previous,special.start()),ids);ids.add(vocab.get(special.group()));previous=special.end();}
        append(text.substring(previous),ids);
        if(ids.size()>max-1)ids=new ArrayList<>(ids.subList(0,max-1));ids.add(102);int[] out=new int[ids.size()];for(int i=0;i<out.length;i++)out[i]=ids.get(i);return out;
    }
    private void append(String text,List<Integer> ids){
        StringBuilder word=new StringBuilder();
        for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);int type=Character.getType(cp);
            if(cp==0||cp==0xfffd)continue;
            boolean whitespace=Character.isWhitespace(cp)||Character.isSpaceChar(cp);
            if(!whitespace&&(type==Character.CONTROL||type==Character.FORMAT))continue;
            boolean punctuation=(cp>=33&&cp<=47)||(cp>=58&&cp<=64)||(cp>=91&&cp<=96)||(cp>=123&&cp<=126)||type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION||type==Character.START_PUNCTUATION||type==Character.END_PUNCTUATION||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION||type==Character.OTHER_PUNCTUATION;
            if(whitespace||punctuation||chinese(cp)){flush(word,ids);if(!whitespace)pieces(new String(Character.toChars(cp)),ids);}else word.appendCodePoint(cp);
        }flush(word,ids);
    }
    private void flush(StringBuilder word,List<Integer> ids){if(word.length()>0){pieces(word.toString(),ids);word.setLength(0);}}
    private void pieces(String token,List<Integer> ids){
        if(token.codePointCount(0,token.length())>100){ids.add(100);return;}
        int origin=ids.size(),start=0;
        while(start<token.length()){
            int end=token.length(),found=-1;
            while(end>start){Integer id=vocab.get((start==0?"":"##")+token.substring(start,end));if(id!=null){found=id;break;}end=token.offsetByCodePoints(end,-1);}
            if(found<0){while(ids.size()>origin)ids.remove(ids.size()-1);ids.add(100);return;}ids.add(found);start=end;
        }
    }
    private static boolean chinese(int cp){return(cp>=0x4e00&&cp<=0x9fff)||(cp>=0x3400&&cp<=0x4dbf)||(cp>=0x20000&&cp<=0x2a6df)||(cp>=0x2a700&&cp<=0x2b73f)||(cp>=0x2b740&&cp<=0x2b81f)||(cp>=0x2b820&&cp<=0x2ceaf)||(cp>=0xf900&&cp<=0xfaff)||(cp>=0x2f800&&cp<=0x2fa1f);}
}
