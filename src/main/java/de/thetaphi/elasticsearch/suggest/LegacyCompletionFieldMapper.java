/*
 * Forked and modified from Elasticsearch by the Generics Policeman.
 *
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package de.thetaphi.elasticsearch.suggest;

import static org.elasticsearch.index.mapper.TypeParsers.parseMultiField;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.mapper.CompletionFieldMapper2x;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.search.suggest.completion2x.AnalyzingCompletionLookupProvider;
import org.elasticsearch.search.suggest.completion2x.context.ContextBuilder;
import org.elasticsearch.search.suggest.completion2x.context.ContextMapping;

/** Hack to add a new field type that uses the Elasticsearch 2.x Completion Suggester index format. */
public class LegacyCompletionFieldMapper extends CompletionFieldMapper2x {
  
  public static final String CONTENT_TYPE = "legacy_completion";
  
  public static class Builder extends FieldMapper.Builder<Builder,LegacyCompletionFieldMapper> {
    
    private boolean preserveSeparators = Defaults.DEFAULT_PRESERVE_SEPARATORS;
    private boolean payloads = Defaults.DEFAULT_HAS_PAYLOADS;
    private boolean preservePositionIncrements = Defaults.DEFAULT_POSITION_INCREMENTS;
    private int maxInputLength = Defaults.DEFAULT_MAX_INPUT_LENGTH;
    private SortedMap<String,ContextMapping> contextMapping = ContextMapping.EMPTY_MAPPING;
    
    public Builder(String name) {
      super(name, Defaults.FIELD_TYPE, Defaults.FIELD_TYPE);
      builder = this;
    }
    
    public Builder payloads(boolean payloads) {
      this.payloads = payloads;
      return this;
    }
    
    public Builder preserveSeparators(boolean preserveSeparators) {
      this.preserveSeparators = preserveSeparators;
      return this;
    }
    
    public Builder preservePositionIncrements(boolean preservePositionIncrements) {
      this.preservePositionIncrements = preservePositionIncrements;
      return this;
    }
    
    public Builder maxInputLength(int maxInputLength) {
      if (maxInputLength <= 0) {
        throw new IllegalArgumentException(
            Fields.MAX_INPUT_LENGTH.getPreferredName() + " must be > 0 but was [" + maxInputLength + "]");
      }
      this.maxInputLength = maxInputLength;
      return this;
    }
    
    public Builder contextMapping(SortedMap<String,ContextMapping> contextMapping) {
      this.contextMapping = contextMapping;
      return this;
    }
    
    @Override
    public LegacyCompletionFieldMapper build(Mapper.BuilderContext context) {
      setupFieldType(context);
      CompletionFieldType completionFieldType = (CompletionFieldType) fieldType;
      completionFieldType
          .setProvider(new AnalyzingCompletionLookupProvider(preserveSeparators, preservePositionIncrements, payloads));
      completionFieldType.setContextMapping(contextMapping);
      return new LegacyCompletionFieldMapper(name, fieldType, maxInputLength, context.indexSettings(),
          multiFieldsBuilder.build(this, context), copyTo);
    }
    
  }
  
  public static class TypeParser implements Mapper.TypeParser {
    
    @Override
    public Mapper.Builder<?,?> parse(String name, Map<String,Object> node, ParserContext parserContext)
        throws MapperParsingException {
      Builder builder = new Builder(name);
      NamedAnalyzer indexAnalyzer = null;
      NamedAnalyzer searchAnalyzer = null;
      for (Iterator<Map.Entry<String,Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
        Map.Entry<String,Object> entry = iterator.next();
        String fieldName = entry.getKey();
        Object fieldNode = entry.getValue();
        if (fieldName.equals("type")) {
          continue;
        }
        if (Fields.ANALYZER.equals(fieldName) || // index_analyzer is for backcompat, remove for v3.0
            fieldName.equals("index_analyzer") && parserContext.indexVersionCreated().before(Version.V_2_0_0_beta1)) {
          
          indexAnalyzer = getNamedAnalyzer(parserContext, fieldNode.toString());
          iterator.remove();
        } else if (Fields.SEARCH_ANALYZER.match(fieldName)) {
          searchAnalyzer = getNamedAnalyzer(parserContext, fieldNode.toString());
          iterator.remove();
        } else if (fieldName.equals(Fields.PAYLOADS)) {
          builder.payloads(Boolean.parseBoolean(fieldNode.toString()));
          iterator.remove();
        } else if (Fields.PRESERVE_SEPARATORS.match(fieldName)) {
          builder.preserveSeparators(Boolean.parseBoolean(fieldNode.toString()));
          iterator.remove();
        } else if (Fields.PRESERVE_POSITION_INCREMENTS.match(fieldName)) {
          builder.preservePositionIncrements(Boolean.parseBoolean(fieldNode.toString()));
          iterator.remove();
        } else if (Fields.MAX_INPUT_LENGTH.match(fieldName)) {
          builder.maxInputLength(Integer.parseInt(fieldNode.toString()));
          iterator.remove();
        } else if (parseMultiField(builder, name, parserContext, fieldName, fieldNode)) {
          iterator.remove();
        } else if (fieldName.equals(Fields.CONTEXT)) {
          builder.contextMapping(ContextBuilder.loadMappings(fieldNode, parserContext.indexVersionCreated()));
          iterator.remove();
        }
      }
      
      if (indexAnalyzer == null) {
        if (searchAnalyzer != null) {
          throw new MapperParsingException(
              "analyzer on completion field [" + name + "] must be set when search_analyzer is set");
        }
        indexAnalyzer = searchAnalyzer = parserContext.getIndexAnalyzers().get("simple");
      } else if (searchAnalyzer == null) {
        searchAnalyzer = indexAnalyzer;
      }
      builder.indexAnalyzer(indexAnalyzer);
      builder.searchAnalyzer(searchAnalyzer);
      
      return builder;
    }
    
    private NamedAnalyzer getNamedAnalyzer(ParserContext parserContext, String name) {
      NamedAnalyzer analyzer = parserContext.getIndexAnalyzers().get(name);
      if (analyzer == null) {
        throw new IllegalArgumentException("Can't find default or mapped analyzer with name [" + name + "]");
      }
      return analyzer;
    }
  }
  
  public LegacyCompletionFieldMapper(String simpleName, MappedFieldType fieldType, int maxInputLength,
      Settings indexSettings, MultiFields multiFields, CopyTo copyTo) {
    super(simpleName, fieldType, maxInputLength, indexSettings, multiFields, copyTo);
  }
  
  @Override // HACK ALARM: calls super's toXContent, but patches type name by de-serializing and re-serializing
  public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
    final XContentBuilder innerBuilder = XContentFactory.cborBuilder().startObject();
    super.toXContent(innerBuilder, params);
    final BytesReference bytes = innerBuilder.endObject().bytes();
    @SuppressWarnings("unchecked") final Map<String,Object> map = (Map<String,Object>) XContentHelper
        .convertToMap(bytes, true).v2().values().iterator().next();
    map.put(Fields.TYPE, CONTENT_TYPE); // patch the name :-)
    return builder.field(simpleName(), map);
  }
  
  @Override
  protected String contentType() {
    return CONTENT_TYPE;
  }

}
