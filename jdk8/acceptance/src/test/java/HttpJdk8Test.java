import dev.langchain4j.http.client.sse.*;
import dev.langchain4j.http.client.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpJdk8Test {
 @Test public void parserHandlesUtf8AcrossEveryByteBoundaryAndTrailingEvent(){
  byte[] data=("data: 你好\r\n\r\ndata: tail").getBytes(StandardCharsets.UTF_8);
  ServerSentEventParser.Incremental parser=new DefaultServerSentEventParser().incremental();List<ServerSentEvent> events=new ArrayList<ServerSentEvent>();
  for(byte b:data)events.addAll(parser.feed(ByteBuffer.wrap(new byte[]{b})));events.addAll(parser.flush());
  assertEquals(2,events.size());assertEquals("你好",events.get(0).data());assertEquals("tail",events.get(1).data());
 }
 @Test public void callbackParserHandlesMultilineData(){
  List<ServerSentEvent> events=new ArrayList<ServerSentEvent>();
  new DefaultServerSentEventParser().parse(new ByteArrayInputStream("event: update\ndata: first\ndata: second\n\n".getBytes(StandardCharsets.UTF_8)),new ServerSentEventListener(){public void onEvent(ServerSentEvent e){events.add(e);}});
  assertEquals(1,events.size());assertEquals("first\nsecond",events.get(0).data());assertEquals("update",events.get(0).event());
 }
 @Test public void serviceLoaderSelectsOkHttp(){assertEquals("dev.langchain4j.http.client.okhttp.OkHttpClientBuilder",HttpClientBuilderLoader.loadHttpClientBuilder().getClass().getName());}
}
