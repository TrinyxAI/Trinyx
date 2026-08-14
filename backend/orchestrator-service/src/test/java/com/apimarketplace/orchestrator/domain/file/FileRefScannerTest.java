package com.apimarketplace.orchestrator.domain.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileRefScanner}.
 *
 * <p>This decides which files a step claims as its own, so two things matter beyond finding the
 * obvious ref: it must find refs wherever a tool actually puts them (nested, in lists, several
 * levels down), and it must never throw - it runs on the output of a step that has already
 * succeeded, and nothing here is worth failing that step over.</p>
 */
@DisplayName("FileRefScanner.collectFileIds")
class FileRefScannerTest {

    private static Map<String, Object> fileRef(String id) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("_type", "file");
        ref.put("path", "1/general/catalog-binary/x.mp3");
        ref.put("name", "x.mp3");
        ref.put("mimeType", "audio/mpeg");
        ref.put("size", 218800);
        if (id != null) {
            ref.put("id", id);
        }
        return ref;
    }

    @Nested
    @DisplayName("finds refs where tools actually put them")
    class Finds {

        @Test
        @DisplayName("a ref projected under a field - the shape a binary catalog response produces")
        void refUnderAField() {
            // BinaryResponseHandler projects the upload under the schema's file-ref key.
            Map<String, Object> output = Map.of("audio", fileRef("11111111-1111-1111-1111-111111111111"));

            assertThat(FileRefScanner.collectFileIds(output))
                    .containsExactly("11111111-1111-1111-1111-111111111111");
        }

        @Test
        @DisplayName("a ref at the top level")
        void refAtTopLevel() {
            assertThat(FileRefScanner.collectFileIds(fileRef("aaa"))).containsExactly("aaa");
        }

        @Test
        @DisplayName("refs inside a list of results")
        void refsInsideAList() {
            Map<String, Object> output = Map.of("results", List.of(fileRef("a"), fileRef("b")));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("a ref several levels down")
        void refDeeplyNested() {
            Map<String, Object> output = Map.of("data", Map.of("items", List.of(
                    Map.of("attachment", fileRef("deep")))));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("deep");
        }

        @Test
        @DisplayName("the same id twice yields one entry - adoption is per row, not per mention")
        void deduplicates() {
            Map<String, Object> output = Map.of("a", fileRef("same"), "b", fileRef("same"));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("same");
        }
    }

    @Nested
    @DisplayName("ignores what is not an adoptable ref")
    class Ignores {

        @Test
        @DisplayName("a ref with no id - a legacy ref predating the opaque handle")
        void refWithoutId() {
            assertThat(FileRefScanner.collectFileIds(Map.of("audio", fileRef(null)))).isEmpty();
        }

        @Test
        @DisplayName("a map that merely looks file-ish but is not typed as a file")
        void untypedMap() {
            Map<String, Object> notARef = Map.of("path", "some/path", "name", "x.mp3", "id", "nope");

            assertThat(FileRefScanner.collectFileIds(Map.of("thing", notARef))).isEmpty();
        }

        @Test
        @DisplayName("a different _type")
        void otherType() {
            assertThat(FileRefScanner.collectFileIds(Map.of("x", Map.of("_type", "table", "id", "nope")))).isEmpty();
        }

        @Test
        @DisplayName("an output with no refs at all, and the empty cases")
        void noRefs() {
            assertThat(FileRefScanner.collectFileIds(Map.of("status", "ok", "count", 3))).isEmpty();
            assertThat(FileRefScanner.collectFileIds(Map.of())).isEmpty();
            assertThat(FileRefScanner.collectFileIds(null)).isEmpty();
            assertThat(FileRefScanner.collectFileIds("just a string")).isEmpty();
        }

        @Test
        @DisplayName("a blank id is not an id")
        void blankId() {
            Map<String, Object> ref = fileRef(null);
            ref.put("id", "   ");

            assertThat(FileRefScanner.collectFileIds(Map.of("audio", ref))).isEmpty();
        }
    }

    @Nested
    @DisplayName("never throws on a hostile payload")
    class Survives {

        @Test
        @DisplayName("a self-referencing structure terminates instead of spinning")
        void handlesCycles() {
            Map<String, Object> outer = new HashMap<>();
            outer.put("self", outer);
            outer.put("audio", fileRef("cyclic"));

            assertThat(FileRefScanner.collectFileIds(outer)).containsExactly("cyclic");
        }

        @Test
        @DisplayName("a list that contains itself terminates")
        void handlesListCycles() {
            List<Object> list = new ArrayList<>();
            list.add(fileRef("in-list"));
            list.add(list);

            assertThat(FileRefScanner.collectFileIds(Map.of("items", list))).containsExactly("in-list");
        }

        @Test
        @DisplayName("nulls inside maps and lists are stepped over")
        void handlesNulls() {
            Map<String, Object> output = new HashMap<>();
            output.put("nothing", null);
            List<Object> items = new ArrayList<>();
            items.add(null);
            items.add(fileRef("present"));
            output.put("items", items);

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("present");
        }

        @Test
        @DisplayName("a ref buried below the depth cap is given up on, not chased forever")
        void boundedDepth() {
            // 30 levels: past the cap. Giving up costs a file at the root of the Files browser;
            // not giving up would cost the step.
            Map<String, Object> node = fileRef("too-deep");
            for (int i = 0; i < 30; i++) {
                node = new HashMap<>(Map.of("wrap", node));
            }

            assertThat(FileRefScanner.collectFileIds(node)).isEmpty();
        }
    }
}
