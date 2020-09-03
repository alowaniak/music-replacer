package nl.alowaniak.runelite.musicreplacer;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

@Slf4j
@Singleton
class YouTubeSearcher
{
	@Inject
	@Named("musicReplacerExecutor")
	private ExecutorService executor;

	@Inject
	public YouTubeSearcher(OkHttpClient http)
	{
		NewPipe.init(new Downloader()
		{
			@Override
			public Response execute(@Nonnull Request request) throws IOException
			{
				okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder()
						.url(request.url())
						.method(
								request.httpMethod(),
								request.dataToSend() == null ? null : RequestBody.create(null,  request.dataToSend())
						);
				request.headers().forEach((k, v) -> v.forEach(e -> reqBuilder.addHeader(k, e)));

				okhttp3.Response res = http.newCall(reqBuilder.build()).execute();
				return new Response(res.code(), res.message(), res.headers().toMultimap(), res.body().string(), res.request().url().toString());
			}
		});
	}

	/**
	 * Does a (paginated of 4 items) search
	 *
	 * @param term the search {@code term} to search for.
	 * @param resultCollector this consumer will be called with the first argument being (at most) 4 search hits.
	 *                        The second argument can be called by the consumer to "continue the search"
	 *                        in which case this {@code resultCollector} will be called again with the next search hits.
	 */
	public void search(String term, BiConsumer<List<StreamInfoItem>, Runnable> resultCollector)
	{
		executor.submit(() ->
		{
			try
			{
				SearchExtractor search = ServiceList.YouTube.getSearchExtractor(term);

				search.fetchPage();
				ListExtractor.InfoItemsPage<InfoItem> page = search.getInitialPage();

				paginateSearch(resultCollector, search, 0, page);
			}
			catch (ExtractionException | IOException e)
			{
				log.warn("Something went wrong when searching youtube for: " + term, e);
				resultCollector.accept(Collections.emptyList(), () -> {});
			}
		});
	}

	private void paginateSearch(BiConsumer<List<StreamInfoItem>, Runnable> resultCollector, SearchExtractor search, int start, ListExtractor.InfoItemsPage<InfoItem> page)
	{
		List<StreamInfoItem> hits = page.getItems().stream()
			.filter(e -> e instanceof StreamInfoItem).map(e -> (StreamInfoItem) e)
			.collect(Collectors.toList());

		resultCollector.accept(hits.subList(Math.min(start, hits.size()), Math.min(start+4, hits.size())), () -> executor.submit(() ->
			{
				try
				{
					ListExtractor.InfoItemsPage<InfoItem> newPage = page;
					if (start + 4 >= hits.size())
					{
						newPage = search.getPage(page.getNextPage());
					}
					paginateSearch(resultCollector, search, page != newPage ? 0 : start + 4, newPage);
				} catch (IOException | ExtractionException e)
				{
					log.warn("Something went wrong when searching next page.", e);
					resultCollector.accept(Collections.emptyList(), () -> {});
				}
			}
		));
	}
}
