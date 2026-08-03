package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExtractFromFileNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("EXTRACT_FROM_FILE")
            .label("Extract from File")
            .category("core")
            .variablePrefix("core")
            .description("Extracts structured data from files (CSV, Excel, JSON)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Extracted rows/items")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("format")
                    .type("string")
                    .description("Detected file format")
                    .defaultValue("csv")
                    .build(),
                OutputFieldDef.builder()
                    .key("rowCount")
                    .type("number")
                    .description("Number of rows extracted")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("columnCount")
                    .type("number")
                    .description("Number of columns detected")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("columns")
                    .type("array")
                    .description("Column names/headers")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether extraction was successful")
                    .defaultValue(false)
                    .build(),
                // Text-mode fields. ExtractFromFileNode computes all of these, but until they
                // were declared here GenericOutputSchemaMapper dropped them before persistence:
                // the node emitted them, node_type_documentation advertised them (V78, V360),
                // and a template against them resolved to an empty string (or null for a pure
                // {{...}}), silently, with the run still reporting COMPLETED.
                // Deliberately NO defaultValue: they are conditional, so the mapper omits the
                // key entirely rather than storing a misleading zero on the structured path.
                OutputFieldDef.builder()
                    .key("mode")
                    .type("string")
                    .description("Extraction mode that ran: 'structured' (CSV/Excel/JSON rows) or 'text'")
                    .build(),
                OutputFieldDef.builder()
                    .key("total_chunks")
                    .type("number")
                    .description("Text mode: number of chunks the extracted text was split into. Equals the length of items. Absent in structured mode")
                    .build(),
                OutputFieldDef.builder()
                    .key("text_length")
                    .type("number")
                    .description("Text mode: character length of the extracted text before chunking. Absent in structured mode")
                    .build(),
                OutputFieldDef.builder()
                    .key("chunking_strategy")
                    .type("string")
                    .description("Text mode with chunking enabled: the strategy used to split the text. Absent when chunking is off")
                    .build(),
                OutputFieldDef.builder()
                    .key("chunk_size")
                    .type("number")
                    .description("Text mode with chunking enabled: configured chunk size, expressed in chunk_unit. Absent when chunking is off")
                    .build(),
                OutputFieldDef.builder()
                    .key("overlap")
                    .type("number")
                    .description("Text mode with chunking enabled: overlap between consecutive chunks, expressed in chunk_unit. Absent when chunking is off")
                    .build(),
                OutputFieldDef.builder()
                    .key("chunk_unit")
                    .type("string")
                    .description("Text mode with chunking enabled: unit chunk_size and overlap are counted in. Absent when chunking is off")
                    .build()
            ))
            .keywords(List.of("extract", "file", "csv", "excel", "import"))
            .build();
    }
}
