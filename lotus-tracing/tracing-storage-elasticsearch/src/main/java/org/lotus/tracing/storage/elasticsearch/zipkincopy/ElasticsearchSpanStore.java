
package org.lotus.tracing.storage.elasticsearch.zipkincopy;

import org.lotus.tracing.storage.elasticsearch.IndexNameFormatter;
import zipkin2.Call;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.internal.client.Aggregation;
import zipkin2.elasticsearch.internal.client.HttpCall;
import zipkin2.elasticsearch.internal.client.HttpCall.BodyConverter;
import zipkin2.elasticsearch.internal.client.SearchCallFactory;
import zipkin2.elasticsearch.internal.client.SearchRequest;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.SpanStore;

import java.util.*;

import static java.util.Arrays.asList;

;

public class ElasticsearchSpanStore implements SpanStore {

  public static final String SPAN = "span";
  public  static final String DEPENDENCY = "dependency";
  /** To not produce unnecessarily long queries, we don't look back further than first ES support */
  static final long EARLIEST_MS = 1456790400000L; // March 2016

  protected final SearchCallFactory search;
  final String[] allSpanIndices;
  protected final IndexNameFormatter indexNameFormatter;
  final boolean strictTraceId, searchEnabled;
  final int namesLookback;

  public ElasticsearchSpanStore(ElasticsearchStorage es,boolean searchEnabled,IndexNameFormatter indexNameFormatter ) {
    this.search = new SearchCallFactory(es.http());
    this.allSpanIndices = new String[] {indexNameFormatter.formatType(SPAN)};
    this.indexNameFormatter = indexNameFormatter;
    this.strictTraceId = es.strictTraceId();
    this.searchEnabled = searchEnabled;
    this.namesLookback = es.namesLookback();
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    if (!searchEnabled) return Call.emptyList();

    long endMillis = request.endTs();
    long beginMillis = Math.max(endMillis - request.lookback(), EARLIEST_MS);

    SearchRequest.Filters filters = new SearchRequest.Filters();
    filters.addRange("timestamp_millis", beginMillis, endMillis);
    if (request.serviceName() != null) {
      filters.addTerm("localEndpoint.serviceName", request.serviceName());
    }

    if (request.spanName() != null) {
      filters.addTerm("name", request.spanName());
    }

    for (Map.Entry<String, String> kv : request.annotationQuery().entrySet()) {
      if (kv.getValue().isEmpty()) {
        filters.addTerm("_q", kv.getKey());
      } else {
        filters.addTerm("_q", kv.getKey() + "=" + kv.getValue());
      }
    }

    if (request.minDuration() != null) {
      filters.addRange("duration", request.minDuration(), request.maxDuration());
    }

    // We need to filter to traces that contain at least one span that matches the request,
    // but the zipkin API is supposed to order traces by first span, regardless of if it was
    // filtered or not. This is not possible without either multiple, heavyweight queries
    // or complex multiple indexing, defeating much of the elegance of using elasticsearch for this.
    // So we fudge and order on the first span among the filtered spans - in practice, there should
    // be no significant difference in user experience since span start times are usually very
    // close to each other in human time.
    Aggregation traceIdTimestamp = Aggregation.terms("traceId", request.limit())
      .addSubAggregation(Aggregation.min("timestamp_millis"))
      .orderBy("timestamp_millis", "desc");

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    SearchRequest esRequest = SearchRequest.create(indices)
      .filters(filters).addAggregation(traceIdTimestamp);

    HttpCall<List<String>> traceIdsCall = search.newCall(esRequest, BodyConverters.KEYS);

    // When we receive span results, we need to group them by trace ID
    BodyConverter<List<List<Span>>> converter = content -> {
      List<Span> input = BodyConverters.SPANS.convert(content);
      List<List<Span>> traces = groupByTraceId(input, strictTraceId);

      // Due to tokenization of the trace ID, our matches are imprecise on Span.traceIdHigh
      for (Iterator<List<Span>> trace = traces.iterator(); trace.hasNext(); ) {
        List<Span> next = trace.next();
        if (next.get(0).traceId().length() > 16 && !request.test(next)) {
          trace.remove();
        }
      }
      return traces;
    };

    return traceIdsCall.flatMap(new Call.FlatMapper<List<String>, List<List<Span>>>() {
      @Override public Call<List<List<Span>>> map(List<String> input) {
        if (input.isEmpty()) return Call.emptyList();

        SearchRequest getTraces = SearchRequest.create(indices).terms("traceId", input);
        return search.newCall(getTraces, converter);
      }
    });
  }

  @Override public Call<List<Span>> getTrace(String traceId) {
    // make sure we have a 16 or 32 character trace ID
    traceId = Span.normalizeTraceId(traceId);

    // Unless we are strict, truncate the trace ID to 64bit (encoded as 16 characters)
    if (!strictTraceId && traceId.length() == 32) traceId = traceId.substring(16);

    SearchRequest request = SearchRequest.create(asList(allSpanIndices)).term("traceId", traceId);
    return search.newCall(request, BodyConverters.SPANS);
  }

  @Override public Call<List<String>> getServiceNames() {
    if (!searchEnabled) return Call.emptyList();

    long endMillis = System.currentTimeMillis();
    long beginMillis = endMillis - namesLookback;

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    // Service name queries include both local and remote endpoints. This is different than
    // Span name, as a span name can only be on a local endpoint.
    SearchRequest.Filters filters = new SearchRequest.Filters();
    filters.addRange("timestamp_millis", beginMillis, endMillis);
    SearchRequest request = SearchRequest.create(indices)
      .filters(filters)
      .addAggregation(Aggregation.terms("localEndpoint.serviceName", Integer.MAX_VALUE))
      .addAggregation(Aggregation.terms("remoteEndpoint.serviceName", Integer.MAX_VALUE));
    return search.newCall(request, BodyConverters.KEYS);
  }

  @Override public Call<List<String>> getSpanNames(String serviceName) {
    if (!searchEnabled) return Call.emptyList();

    if ("".equals(serviceName)) return Call.emptyList();

    long endMillis = System.currentTimeMillis();
    long beginMillis = endMillis - namesLookback;

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    // A span name is only valid on a local endpoint, as a span name is defined locally
    SearchRequest.Filters filters = new SearchRequest.Filters()
      .addRange("timestamp_millis", beginMillis, endMillis)
      .addTerm("localEndpoint.serviceName", serviceName.toLowerCase(Locale.ROOT));

    SearchRequest request = SearchRequest.create(indices)
      .filters(filters)
      .addAggregation(Aggregation.terms("name", Integer.MAX_VALUE));

    return search.newCall(request, BodyConverters.KEYS);
  }

  @Override public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    long beginMillis = Math.max(endTs - lookback, EARLIEST_MS);

    // We just return all dependencies in the days that fall within endTs and lookback as
    // dependency links themselves don't have timestamps.
    List<String> indices = indexNameFormatter.formatTypeAndRange(DEPENDENCY, beginMillis, endTs);
    if (indices.isEmpty()) return Call.emptyList();

    return search.newCall(SearchRequest.create(indices), BodyConverters.DEPENDENCY_LINKS);
  }

  static List<List<Span>> groupByTraceId(Collection<Span> input, boolean strictTraceId) {
    if (input.isEmpty()) return Collections.emptyList();

    Map<String, List<Span>> groupedByTraceId = new LinkedHashMap<>();
    for (Span span : input) {
      String traceId = strictTraceId || span.traceId().length() == 16
        ? span.traceId()
        : span.traceId().substring(16);
      if (!groupedByTraceId.containsKey(traceId)) {
        groupedByTraceId.put(traceId, new ArrayList<>());
      }
      groupedByTraceId.get(traceId).add(span);
    }
    return new ArrayList<>(groupedByTraceId.values());
  }
}
