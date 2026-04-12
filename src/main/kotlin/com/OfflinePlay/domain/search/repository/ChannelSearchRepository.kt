package com.contenido.domain.search.repository

import com.contenido.domain.search.document.ChannelDocument
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface ChannelSearchRepository : ElasticsearchRepository<ChannelDocument, String>
