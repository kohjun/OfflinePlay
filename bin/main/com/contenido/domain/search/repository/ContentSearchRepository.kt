package com.contenido.domain.search.repository

import com.contenido.domain.search.document.ContentDocument
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface ContentSearchRepository : ElasticsearchRepository<ContentDocument, String>
