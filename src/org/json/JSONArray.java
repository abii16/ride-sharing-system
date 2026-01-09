package org.json;

import java.util.*;

public class JSONArray {
    private final List<Object> list = new ArrayList<>();

    public JSONArray() {}

    public JSONArray put(Object value) {
        list.add(value);
        return this;
    }

    public Object get(int index) {
        return list.get(index);
    }
    
    public int length() {
        return list.size();
    }

    public int getInt(int index) {
        Object o = get(index);
        if (o instanceof Number) return ((Number)o).intValue();
        return Integer.parseInt(o.toString());
    }
    
    public JSONObject getJSONObject(int index) {
        Object o = get(index);
        if (o instanceof JSONObject) return (JSONObject)o;
        throw new RuntimeException("Element at " + index + " is not a JSONObject");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append(JSONObject.valueToString(o));
        }
        sb.append("]");
        return sb.toString();
    }
}
