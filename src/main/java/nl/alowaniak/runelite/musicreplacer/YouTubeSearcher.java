package nl.alowaniak.runelite.musicreplacer;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static net.runelite.http.api.RuneLiteAPI.GSON;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.MUSIC_REPLACER_API;

@Slf4j
@Singleton
class YouTubeSearcher
{

    @Inject
	private OkHttpClient http;
	@Inject
	@Named(MusicReplacerPlugin.MUSIC_REPLACER_EXECUTOR)
	private ExecutorService executor;

	/**
	 * @param term the {@code term} to search for.
	 * @param resultCollector will be called with the found results.
	 */
	public void search(String term, Consumer<List<SearchResult>> resultCollector)
	{
		executor.submit(() -> resultCollector.accept(getSearchResults(term)));
	}

    private List<SearchResult> getSearchResults(String term) {
        try (Response res = getSearchResponse(term))
        {
            if (!res.isSuccessful()) throw new IOException(res.code() + ": " + res.message() + " " + res.body().string());

            return GSON.fromJson(res.body().string(), SearchResult.LIST_TYPE.getType());
        }
        catch (IOException e)
        {
            log.warn("Something went wrong when searching for " + term, e);
            return Collections.emptyList();
        }
    }

    private Response getSearchResponse(String term) throws IOException {
        return http.newBuilder().readTimeout(1, TimeUnit.MINUTES)
            .build()
            .newCall(new Request.Builder()
                .url(MUSIC_REPLACER_API + "search/" + URLEncoder.encode(term, StandardCharsets.UTF_8.toString()))
                .build()
            ).execute();
    }
}
