package com.agentstudio.knowledge;

import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

    public String extract(InputStream input, String fileName) throws Exception {
        var parser = new AutoDetectParser();
        var handler = new BodyContentHandler(-1);
        var metadata = new Metadata();
        metadata.set("resourceName", fileName);
        parser.parse(input, handler, metadata, new ParseContext());
        return handler.toString().trim();
    }
}
