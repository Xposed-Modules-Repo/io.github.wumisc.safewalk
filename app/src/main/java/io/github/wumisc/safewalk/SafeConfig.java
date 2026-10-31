package io.github.wumisc.safewalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SafeConfig {
    private final File confFile;
    private final Map<String,String> map = new HashMap<>();

    public SafeConfig(){
        confFile = new File(SafeConstant.CONF_PATH);
        if(!confFile.getParentFile().exists()) confFile.getParentFile().mkdirs();
        load();
    }

    public void load(){
        map.clear();
        if(!confFile.exists()) return;
        try(BufferedReader br = new BufferedReader(new FileReader(confFile))){
            String line;
            while((line = br.readLine()) != null){
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                String[] kv = line.split("=",2);
                if(kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
            }
        }catch (IOException e){
            DiagLogger.getInstance(null).log("conf load err:"+e.getMessage());
        }
    }

    public void save(){
        try(FileWriter fw = new FileWriter(confFile)){
            for(Map.Entry<String,String> entry : map.entrySet()){
                fw.write(entry.getKey()+"="+entry.getValue()+"\n");
            }
        }catch (IOException e){
            DiagLogger.getInstance(null).log("conf save err:"+e.getMessage());
        }
    }

    public boolean getBool(String key, boolean def){
        String v = map.get(key);
        if(v == null) return def;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public void setBool(String key, boolean val){
        map.put(key, val ? "1":"0");
        save();
    }
}
