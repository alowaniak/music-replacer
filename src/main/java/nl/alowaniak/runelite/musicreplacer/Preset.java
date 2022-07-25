package nl.alowaniak.runelite.musicreplacer;

import com.google.gson.reflect.TypeToken;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
public class Preset {
    public static final TypeToken<List<Preset>> LIST_TYPE = new TypeToken<List<Preset>>() {};

    String name;
    String credits;
    Map<String, SearchResult> tracks;
}
