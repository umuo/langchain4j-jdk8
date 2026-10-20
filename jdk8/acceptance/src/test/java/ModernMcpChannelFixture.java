import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.*;

/** Synthetic fixed-baseline protocol fixture; never used by a production module. */
public class ModernMcpChannelFixture {
    static final ObjectMapper JSON=new ObjectMapper();
    final Supplier<String> mode;final int generation;final Consumer<String> output;final Runnable drop;
    int discovers,initializes,executed,continuations,cancellations,badMetadata;boolean changed;
    final Map<Long,JsonNode> subscriptions=new LinkedHashMap<>();
    public ModernMcpChannelFixture(Supplier<String> mode,int generation,Consumer<String> output,Runnable drop){this.mode=mode;this.generation=generation;this.output=output;this.drop=drop;}
    public void accept(String text)throws Exception{
        JsonNode request=JSON.readTree(text);String method=request.path("method").asText();JsonNode params=request.path("params");long id=request.path("id").asLong();
        if(initializes==0&&!method.equals("initialize")&&!params.path("_meta").path("io.modelcontextprotocol/protocolVersion").asText().equals("2026-07-28")){
            badMetadata++;
            if(request.has("id")){send(map("jsonrpc","2.0","id",id,"error",map("code",-32602,"message","Wrong negotiated metadata")));return;}
        }
        if(!request.has("id")){if(method.equals("notifications/cancelled")){cancellations++;subscriptions.remove(params.path("requestId").asLong());}return;}
        Object result;
        switch(method){
            case "server/discover":discovers++;
                if(mode.get().equals("legacyOnly")){send(map("jsonrpc","2.0","id",id,"error",map("code",-32601,"message","legacy only")));return;}
                if(mode.get().equals("recoveryHold")&&generation>1)return;
                result=map("supportedVersions",Arrays.asList("2026-07-28"),"capabilities",map("tools",map(),"resources",map()),"instructions","channel-"+generation,"_meta",map("io.modelcontextprotocol/serverInfo",map("name","modern","version","1")));break;
            case "initialize":initializes++;result=map("protocolVersion",params.path("protocolVersion").asText(),"capabilities",map("tools",map(),"resources",map()),"serverInfo",map("name","legacy","version","1"));break;
            case "tools/list":result=map("tools",Arrays.asList(map("name",changed?"changed":mode.get().equals("generations")?"echo"+generation:"echo","inputSchema",map("type","object"))));break;
            case "tools/call":
                String action=params.path("arguments").path("action").asText();
                if(action.equals("drop")){drop.run();return;}
                if(action.equals("serverCancel")){send(map("jsonrpc","2.0","method","notifications/cancelled","params",map("requestId",id,"reason","server stopped")));return;}
                if(action.equals("continue")||action.equals("continueHold")){
                    if(!params.has("requestState")){continuations++;reply(id,map("resultType","input_required","requestState","state"));return;}
                }
                if(action.equals("hold")||action.equals("continueHold")){send(map("jsonrpc","2.0","method","notifications/progress","params",map("progressToken","started","progress",1)));return;}
                if(action.equals("listChange")){changed=true;send(map("jsonrpc","2.0","method","notifications/tools/list_changed"));}
                if(action.equals("status"))result=map("content",Arrays.asList(map("type","text","text",JSON.writeValueAsString(map("discovers",discovers,"initializes",initializes,"executed",executed,"continuations",continuations,"cancellations",cancellations,"activeSubscriptions",subscriptions.size(),"badMetadata",badMetadata)))));
                else{executed++;result=map("content",Arrays.asList(map("type","text","text","中文 🚀")));}break;
            case "resources/read":result=map("contents",Arrays.asList(map("uri","test://data","text","data")));break;
            case "prompts/get":result=map("messages",Arrays.asList(map("role","user","content",map("type","text","text","hello"))));break;
            case "subscriptions/listen":subscriptions.put(id,params.path("notifications"));
                if(mode.get().equals("noAck"))return;
                send(map("jsonrpc","2.0","method","notifications/subscriptions/acknowledged","params",map("_meta",map("io.modelcontextprotocol/subscriptionId",id),"notifications",mode.get().equals("decline")?map("resourceSubscriptions",Collections.emptyList()):JSON.convertValue(params.path("notifications"),Map.class))));
                if(params.path("notifications").has("resourceSubscriptions"))send(map("jsonrpc","2.0","method","notifications/resources/updated","params",map("uri","test://data")));return;
            default:result=map();
        }
        reply(id,result);
    }
    void reply(long id,Object result)throws Exception{send(map("jsonrpc","2.0","id",id,"result",result));}
    void send(Object object)throws Exception{output.accept(JSON.writeValueAsString(object));}
    static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
