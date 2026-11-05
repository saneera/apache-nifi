package com.flowdeployer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.flowdeployer.deploy.ProgressParser.Parsed;
import com.flowdeployer.deploy.ProgressParser.Progress;
import com.flowdeployer.deploy.ProgressParser.Summary;
import com.flowdeployer.web.Dtos.ProgressEvent;
import org.junit.jupiter.api.Test;

class ProgressParserTest {

    @Test
    void parsesProgressMarkers() {
        ProgressParser p = new ProgressParser();
        ProgressParser.Result r = p.parse("::progress flow=gateway-ingest stage=REGISTRY_UPLOAD status=done detail=uploaded v15 to bucket x");
        assertThat(r.isMarker()).isTrue();
        assertThat(r.events()).singleElement().isInstanceOfSatisfying(Progress.class, pr ->
                assertThat(pr.event()).isEqualTo(new ProgressEvent("gateway-ingest", "REGISTRY_UPLOAD", "done", "uploaded v15 to bucket x")));
    }

    @Test
    void parsesSummaryMarker() {
        ProgressParser p = new ProgressParser();
        List<Parsed> ev = p.parse("::summary total=5 deployed=3 skipped=2").events();
        assertThat(ev).singleElement().isInstanceOfSatisfying(Summary.class, s -> {
            assertThat(s.event().total()).isEqualTo(5);
            assertThat(s.event().deployed()).isEqualTo(3);
            assertThat(s.event().skipped()).isEqualTo(2);
        });
    }

    @Test
    void ignoresInvalidMarkers() {
        ProgressParser p = new ProgressParser();
        assertThat(p.parse("::progress flow=x stage=NOPE status=done").events()).isEmpty();
    }

    @Test
    void fallbackFollowsExistingScriptLogs() {
        ProgressParser p = new ProgressParser();
        assertThat(p.parse("[SECTION] 2026-11-05 11:24:03 Processing gateway-ingest").events())
                .extracting(e -> ((Progress) e).event().stage()).containsExactly("PREPARE");
        p.parse("[INFO] 2026-11-05 11:24:04 Local hash : abc");
        List<Parsed> done = p.parse("[INFO] 2026-11-05 11:24:09 Done: gateway-ingest").events();
        assertThat(done).isNotEmpty();
        assertThat(((Progress) done.get(done.size() - 1)).event().stage()).isEqualTo("START");
    }

    @Test
    void fallbackStopsOnceMarkersAppear() {
        ProgressParser p = new ProgressParser();
        p.parse("::progress flow=a stage=PREPARE status=started");
        assertThat(p.parse("[SECTION] 2026-11-05 Processing b").events()).isEmpty();
    }
}
