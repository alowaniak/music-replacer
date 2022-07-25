package nl.alowaniak.runelite.musicreplacer;

import com.google.gson.reflect.TypeToken;
import lombok.Data;

import java.util.List;

@Data
public class SearchResult {
    public static final TypeToken<List<SearchResult>> LIST_TYPE = new TypeToken<List<SearchResult>>() {};

    String id;
    String name;
    long duration;
    String uploader;
}
