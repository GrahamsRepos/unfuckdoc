package com.unfuckdoc.opensearch

import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.json.JsonpDeserializer
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.mapping.TypeMapping
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.opensearch.core.bulk.BulkOperation
import org.opensearch.client.opensearch.core.bulk.IndexOperation
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import java.io.StringReader

/** Thin wrapper over the official opensearch-java client: create-mapping + bulk index + search.
 *  Raw JSON bodies (mapping, query) are parsed into typed objects via each type's `_DESERIALIZER`. */
class OpenSearchService(host: String = "localhost", port: Int = 9200) {

    private val mapper = JacksonJsonpMapper()

    private val client: OpenSearchClient? = runCatching {
        val transport = ApacheHttpClient5TransportBuilder
            .builder(HttpHost("http", host, port))
            .setMapper(mapper)
            .build()
        OpenSearchClient(transport)
    }.getOrNull()

    private fun <T> parse(json: String, deserializer: JsonpDeserializer<T>): T =
        mapper.jsonProvider().createParser(StringReader(json)).use { p -> deserializer.deserialize(p, mapper) }

    fun available(): Boolean =
        client?.let { runCatching { it.ping().value() }.getOrDefault(false) } ?: false

    /** (Re)create the index from a mapping body ({"properties":{...}}) and bulk-load the docs. */
    fun indexDocs(index: String, mappingBodyJson: String, docs: List<Map<String, Any?>>): Int {
        val c = client ?: error("OpenSearch unavailable")
        if (c.indices().exists { it.index(index) }.value()) c.indices().delete { it.index(index) }
        val mappings = parse(mappingBodyJson, TypeMapping._DESERIALIZER)
        c.indices().create { it.index(index).mappings(mappings) }

        val ops = docs.mapIndexed { i, doc ->
            BulkOperation.Builder().index(
                IndexOperation.Builder<Map<String, Any?>>().index(index).id(i.toString()).document(doc).build()
            ).build()
        }
        if (ops.isNotEmpty()) c.bulk(BulkRequest.Builder().operations(ops).build())
        c.indices().refresh { it.index(index) }
        return c.count { it.index(index) }.count().toInt()
    }

    /** Run a raw query body ({"bool":{...}} / {"match_all":{}}) against the index; return hit sources. */
    fun search(index: String, queryJson: String, size: Int): List<Map<String, Any?>> {
        val c = client ?: error("OpenSearch unavailable")
        val query = parse(queryJson, Query._DESERIALIZER)
        val resp = c.search({ s -> s.index(index).query(query).size(size) }, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return resp.hits().hits().mapNotNull { it.source() as? Map<String, Any?> }
    }
}
