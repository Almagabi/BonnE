package ai.bonne.screenagent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

public final class WorkflowStore {
    private static final String STORE = "bonne_workflow_library_v1";
    private static final String LIBRARY = "library";
    private static final String DRAFT = "draft";
    private WorkflowStore() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(STORE,Context.MODE_PRIVATE); }
    public static synchronized JSONArray all(Context c) {
        SharedPreferences p=prefs(c);
        String raw=p.getString(LIBRARY,"");
        if(raw.isEmpty()) {
            // Import the workflow created by earlier versions, without deleting it.
            String legacy=c.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("workflow","[]");
            try {
                JSONArray old=new JSONArray(legacy);
                if(old.length()>0){
                    JSONArray migrated=new JSONArray();
                    migrated.put(createItem("Imported recording",old));
                    p.edit().putString(LIBRARY,migrated.toString()).commit();
                    return migrated;
                }
            }catch(Exception ignored){}
            return new JSONArray();
        }
        try{return new JSONArray(raw);}catch(Exception ignored){return new JSONArray();}
    }
    private static JSONObject createItem(String name,JSONArray steps) {
        JSONObject item=new JSONObject();
        try{
            item.put("id", UUID.randomUUID().toString());
            item.put("name",name);
            item.put("created",System.currentTimeMillis());
            item.put("steps",new JSONArray(steps.toString()));
        }catch(Exception ignored){}
        return item;
    }
    public static synchronized JSONObject add(Context c,JSONArray steps) {
        JSONArray lib=all(c);
        JSONObject item=createItem("Automation "+(lib.length()+1),steps);
        lib.put(item);
        prefs(c).edit().putString(LIBRARY,lib.toString()).commit();
        clearDraft(c);
        return item;
    }
    public static synchronized boolean remove(Context c,String id) {
        JSONArray lib=all(c),next=new JSONArray();
        for(int i=0;i<lib.length();i++){
            JSONObject it=lib.optJSONObject(i);
            if(it!=null && !it.optString("id").equals(id))next.put(it);
        }
        return prefs(c).edit().putString(LIBRARY,next.toString()).commit();
    }
    public static synchronized boolean rename(Context c,String id,String name) {
        JSONArray lib=all(c);
        for(int i=0;i<lib.length();i++){
            JSONObject it=lib.optJSONObject(i);
            if(it!=null && it.optString("id").equals(id)){
                try{it.put("name",name);}catch(Exception ignored){}
                return prefs(c).edit().putString(LIBRARY,lib.toString()).commit();
            }
        }
        return false;
    }
    public static synchronized void saveDraft(Context c,JSONArray steps) {
        prefs(c).edit().putString(DRAFT,steps.toString()).commit();
    }
    public static synchronized JSONArray draft(Context c) {
        try{return new JSONArray(prefs(c).getString(DRAFT,"[]"));}
        catch(Exception ignored){return new JSONArray();}
    }
    public static synchronized void clearDraft(Context c) {
        prefs(c).edit().remove(DRAFT).commit();
    }
}
