package org.json;

import java.util.*;

public class JSONObject {
    private final Map<String, Object> map = new HashMap<>();

    public JSONObject() {}

    public JSONObject(String source) {
        if (source == null || source.isEmpty()) return;
        try {
            Object parsed = new Parser(source).parseValue();
            if (parsed instanceof JSONObject) {
                this.map.putAll(((JSONObject)parsed).map);
            } else {
                throw new RuntimeException("Source is not a JSON Object");
            }
        } catch (Exception e) {
            e.printStackTrace(); 
        }
    }

    public JSONObject put(String key, Object value) {
        map.put(key, value);
        return this;
    }

    public Object get(String key) {
        Object val = map.get(key);
        if (val == null) throw new RuntimeException("Key " + key + " not found");
        return val;
    }

    public String getString(String key) { return String.valueOf(get(key)); }
    
    public int getInt(String key) { 
        Object o = get(key);
        if (o instanceof Number) return ((Number)o).intValue();
        return Integer.parseInt(o.toString());
    }
    
    public double getDouble(String key) {
        Object o = get(key);
        if (o instanceof Number) return ((Number)o).doubleValue();
        return Double.parseDouble(o.toString());
    }

    public boolean getBoolean(String key) {
        Object o = get(key);
        if (o instanceof Boolean) return (Boolean)o;
        if (o instanceof String) {
            String s = (String)o;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        throw new RuntimeException("Key " + key + " is not a boolean: " + o);
    }
    
    public boolean optBoolean(String key, boolean def) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) {
             if ("true".equalsIgnoreCase((String)val)) return true;
             if ("false".equalsIgnoreCase((String)val)) return false;
        }
        return def;
    }
    
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String def) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : def;
    }
    
    public double optDouble(String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number)val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String)val); } catch (Exception e) {}
        }
        return def;
    }

    public JSONArray optJSONArray(String key) {
        Object val = map.get(key);
        if (val instanceof JSONArray) return (JSONArray) val;
        return null;
    }
    
    public boolean has(String key) {
        return map.containsKey(key);
    }
    
        public JSONObject getJSONObject(String key) {
        Object val = get(key);
        if (val instanceof JSONObject) return (JSONObject) val;
        throw new RuntimeException("Key " + key + " is not a JSONObject");
    }

    public int optInt(String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number)val).intValue();
        if (val instanceof String) {
             try { return Integer.parseInt((String)val); } catch (Exception e){}
        }
        return def;
    }

    public JSONArray getJSONArray(String key) {
        Object val = get(key);
        if (val instanceof JSONArray) return (JSONArray) val;
        throw new RuntimeException("Key " + key + " is not a JSONArray");
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(quote(e.getKey())).append(":");
            sb.append(valueToString(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    public String toString(int indent) { return toString(); }

    private static String quote(String string) {
        if (string == null || string.length() == 0) return "\"\"";
        return "\"" + string.replace("\"", "\\\"") + "\"";
    }

    public static String valueToString(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String)value);
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof JSONObject) return value.toString();
        if (value instanceof JSONArray) return value.toString();
        
        
        return quote(value.toString());
    }

    
    static class Parser {
        String s; int i = 0;
        Parser(String s) { this.s = s.trim(); }
        
        Object parseValue() {
            skipWs();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (Character.isDigit(c) || c == '-') return parseNum();
            if (s.startsWith("true", i)) { i+=4; return true; }
            if (s.startsWith("false", i)) { i+=5; return false; }
            if (s.startsWith("null", i)) { i+=4; return null; }
            throw new RuntimeException("Unexpected char at " + i + ": " + c + " in " + s);
        }
        
        JSONObject parseObj() {
            JSONObject obj = new JSONObject();
            i++; 
            while(true) {
                skipWs();
                if (i >= s.length()) break;
                if (s.charAt(i) == '}') { i++; return obj; }
                String key = parseStr();
                skipWs();
                if (i < s.length() && s.charAt(i) == ':') i++;
                Object val = parseValue();
                obj.put(key, val);
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            return obj;
        }
        
        JSONArray parseArr() {
            JSONArray arr = new JSONArray();
            i++; 
            while(true) {
                skipWs();
                if (i >= s.length()) break;
                if (s.charAt(i) == ']') { i++; return arr; }
                arr.put(parseValue());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            return arr;
        }
        
        String parseStr() {
            i++; 
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) c = s.charAt(i++);
                sb.append(c);
            }
            return sb.toString();
        }
        
        Number parseNum() {
            int start = i;
            while(i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-')) i++;
            String n = s.substring(start, i);
            if (n.contains(".")) return Double.parseDouble(n);
            return Integer.parseInt(n);
        }
        
        void skipWs() {
            while(i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
