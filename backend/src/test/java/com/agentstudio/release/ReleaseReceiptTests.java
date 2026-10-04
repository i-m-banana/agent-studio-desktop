package com.agentstudio.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReleaseReceiptTests {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void truncatedPublishingEvidenceNeverBecomesDeployed() throws Exception {
        var r=mapper.readTree("{\"successful\":true,\"exitCode\":0,\"deployed\":true,\"rolledBack\":false,\"manualInterventionRequired\":false,\"outputTruncated\":true}");
        assertThat(ReleaseTaskService.receiptStatus("publish_remote_release",r)).isEqualTo("UNKNOWN");
    }
    @Test void missingExitCodeAndUncertainReleaseNeverBecomeDeployed() throws Exception {
        var r=mapper.readTree("{\"successful\":true,\"deployed\":true,\"rolledBack\":false,\"manualInterventionRequired\":false}");
        assertThat(ReleaseTaskService.receiptStatus("publish_remote_release",r)).isEqualTo("MANUAL_INTERVENTION");
    }
    @Test void completeSuccessfulPublicationIsPreserved() throws Exception {
        var r=mapper.readTree("{\"successful\":true,\"exitCode\":0,\"deployed\":true,\"rolledBack\":false,\"manualInterventionRequired\":false,\"outputTruncated\":false}");
        assertThat(ReleaseTaskService.receiptStatus("publish_remote_release",r)).isEqualTo("DEPLOYED");
    }
}
