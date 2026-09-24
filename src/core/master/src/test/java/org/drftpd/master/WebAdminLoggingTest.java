package org.drftpd.master;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebAdminLoggingTest {
    @TempDir
    Path logs;

    @Test
    void webAdminDebugAndErrorsGoOnlyToDedicatedLogWithDefaultRootLevel() throws Exception {
        Path source = Path.of("src/main/resources/master/config/log4j2-master.xml");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source.toFile());
        document.getElementsByTagName("Property").item(0).setTextContent(logs.toString());
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer()
                .transform(new DOMSource(document), new StreamResult(xml));

        try (LoggerContext context = new LoggerContext("webadmin-log-routing-test")) {
            var configuration = new XmlConfiguration(context,
                    new ConfigurationSource(new ByteArrayInputStream(xml.toByteArray())));
            context.start(configuration);
            var logger = context.getLogger("org.drftpd.webadmin.master.WebAdminServer");
            logger.debug("webadmin-request-probe");
            logger.info("webadmin-startup-probe");
            logger.warn("webadmin-warning-probe");
            logger.error("webadmin-error-probe", new IllegalStateException("webadmin-cause-probe"));
            context.getLogger("org.drftpd.master.GlobalContext").warn("master-only-probe");
            assertFalse(configuration.getLoggerConfig(logger.getName()).isAdditive());
        }

        String webadmin = Files.readString(logs.resolve("webadmin.log"));
        for (String message : new String[]{"webadmin-request-probe", "webadmin-startup-probe",
                "webadmin-warning-probe", "webadmin-error-probe", "webadmin-cause-probe"}) {
            assertTrue(webadmin.contains(message), message);
        }
        assertFalse(webadmin.contains("master-only-probe"));
        String master = Files.readString(logs.resolve("master.log"));
        assertTrue(master.contains("master-only-probe"));
        assertFalse(master.contains("webadmin-"));
    }
}
