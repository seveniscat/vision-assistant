package com.bz.va.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SummaryBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String region(String verdict, Boolean nameConsistent, String printedName) {
        String consistency = nameConsistent == null ? "null" : nameConsistent.toString();
        return """
            {"verdict": "%s", "paired": true, "name_consistent": %s, "printed_name": "%s",
             "role_name": "角色", "similarity": 0.8}
            """.formatted(verdict, consistency, printedName);
    }

    @Test
    void allMatchPasses() throws Exception {
        var ip = mapper.readTree("{\"regions\": [" + region("match", true, "名") + "]}");
        var summary = SummaryBuilder.build(mapper, ip, 3);
        assertThat(summary.path("overall").asText()).isEqualTo("pass");
        assertThat(summary.path("verdicts").path("match").asInt()).isEqualTo(1);
        assertThat(summary.path("ocr_texts_passed").asInt()).isEqualTo(3);
    }

    @Test
    void anyMismatchFails() throws Exception {
        var ip = mapper.readTree(
                "{\"regions\": [" + region("match", true, "名") + "," + region("mismatch", null, "名") + "]}");
        assertThat(SummaryBuilder.build(mapper, ip, 0).path("overall").asText()).isEqualTo("fail");
    }

    @Test
    void lowConfidenceNeedsReview() throws Exception {
        var ip = mapper.readTree("{\"regions\": [" + region("low_confidence", null, "名") + "]}");
        assertThat(SummaryBuilder.build(mapper, ip, 0).path("overall").asText()).isEqualTo("review");
    }

    @Test
    void nameInconsistentNeedsReviewAndIsListed() throws Exception {
        var ip = mapper.readTree("{\"regions\": [" + region("match", false, "错位名") + "]}");
        var summary = SummaryBuilder.build(mapper, ip, 0);
        assertThat(summary.path("overall").asText()).isEqualTo("review");
        assertThat(summary.path("name_inconsistent").get(0).path("printed_name").asText())
                .isEqualTo("错位名");
    }

    @Test
    void noRegionsNeedsReview() throws Exception {
        var ip = mapper.readTree("{\"regions\": []}");
        assertThat(SummaryBuilder.build(mapper, ip, 0).path("overall").asText()).isEqualTo("review");
    }
}
