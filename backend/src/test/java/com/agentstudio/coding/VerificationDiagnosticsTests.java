package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VerificationDiagnosticsTests {
    @Test void keepsFinalFailureAfterLargePageDumpWithoutUnboundedLogs() {
        var log=new BoundedProcessOutput(8000,16000);
        String value="startup\n"+"PAGE_HTML".repeat(12000)+"\nFINAL_ASSERTION_FAILED\nBUILD FAILURE";
        for(int i=0;i<value.length();i+=2048) {
            var chunk=value.substring(i,Math.min(value.length(),i+2048)).toCharArray();log.append(chunk,chunk.length);
        }
        assertThat(log.truncated()).isTrue();
        assertThat(log.text()).startsWith("startup").endsWith("BUILD FAILURE").contains("FINAL_ASSERTION_FAILED").hasSizeLessThan(24100);
    }
    @Test void exactBoundedLogIsNotMarkedTruncated() {
        var log=new BoundedProcessOutput(3,4);var chars="1234567".toCharArray();log.append(chars,chars.length);
        assertThat(log.text()).isEqualTo("1234567");assertThat(log.truncated()).isFalse();
        log.append(new char[]{'8'},1);assertThat(log.text()).startsWith("123").endsWith("5678");assertThat(log.truncated()).isTrue();
    }
    @Test void readsFreshCountsAndFailingTestFromRegularXmlOnly() throws Exception {
        var xml="<testsuite name=\"example.DemoTest\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.DemoTest\" name=\"returnContext\"><failure message=\"expected tag but was null\">DemoTest.java:42</failure></testcase></testsuite>";
        var reports=MavenTestReports.read(new ByteArrayInputStream(tar("surefire-reports/TEST-example.DemoTest.xml",xml,'0')));
        assertThat(reports).hasSize(1);assertThat(reports.getFirst()).containsEntry("tests",2).containsEntry("failures",1);
        assertThat(reports.getFirst().get("failedTests").toString()).contains("returnContext","expected tag","DemoTest.java:42");
        assertThat(MavenTestReports.read(new ByteArrayInputStream(tar("TEST-example.DemoTest.xml",xml,'2')))).isEmpty();
    }
    @Test void rejectsExternalEntitiesAndMismatchedReportIdentity() {
        assertThatThrownBy(()->MavenTestReports.parse("<!DOCTYPE testsuite SYSTEM 'http://127.0.0.1/secret'><testsuite/>".getBytes(StandardCharsets.UTF_8),"TEST-x.xml"));
        assertThatThrownBy(()->MavenTestReports.parse("<testsuite name='other'/>".getBytes(StandardCharsets.UTF_8),"TEST-x.xml")).hasMessageContaining("身份不符");
    }
    private byte[] tar(String name,String content,char type)throws Exception {
        var bytes=content.getBytes(StandardCharsets.UTF_8);var out=new ByteArrayOutputStream();var header=new byte[512];
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8),0,header,0,name.length());
        var size=String.format("%011o",bytes.length).getBytes(StandardCharsets.US_ASCII);System.arraycopy(size,0,header,124,size.length);header[156]=(byte)type;
        out.write(header);out.write(bytes);out.write(new byte[(512-bytes.length%512)%512]);out.write(new byte[1024]);return out.toByteArray();
    }
}
