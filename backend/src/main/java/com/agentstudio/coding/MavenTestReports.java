package com.agentstudio.coding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read bounded regular report files from a tar stream; never extract paths to the host. */
final class MavenTestReports {
    static List<Map<String,Object>> read(InputStream input) throws Exception {
        var reports=new ArrayList<Map<String,Object>>();long total=0;int entries=0;
        while(true) {
            var header=input.readNBytes(512);
            if(header.length==0)break;
            if(header.length!=512)throw new EOFException("测试报告归档不完整");
            boolean empty=true;for(byte b:header)if(b!=0){empty=false;break;}if(empty)break;
            if(++entries>256)throw new IOException("测试报告条目过多");
            String name=new String(header,0,100,StandardCharsets.UTF_8).split("\0",2)[0];
            String sizeText=new String(header,124,12,StandardCharsets.US_ASCII).replace("\0","").trim();
            long size=Long.parseLong(sizeText.isEmpty()?"0":sizeText,8);
            if(size<0||size>2*1024*1024||(total+=size)>8*1024*1024)throw new IOException("测试报告超出大小限制");
            boolean regular=header[156]==0||header[156]=='0';
            String base=name.substring(name.lastIndexOf('/')+1);
            if(regular&&base.matches("TEST-[A-Za-z0-9_.$-]+\\.xml")) {
                if(reports.size()>=64)throw new IOException("测试报告组数过多");
                reports.add(parse(input.readNBytes((int)size),base));
            } else input.skipNBytes(size);
            input.skipNBytes((512-size%512)%512);
        }
        return List.copyOf(reports);
    }
    static Map<String,Object> parse(byte[] bytes,String file)throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var root=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if(!root.getTagName().equals("testsuite")||!file.equals("TEST-"+root.getAttribute("name")+".xml"))
            throw new IOException("测试报告身份不符");
        var result=new LinkedHashMap<String,Object>();result.put("suite",root.getAttribute("name"));
        for(String key:List.of("tests","failures","errors","skipped")) {
            int count=Integer.parseInt(root.getAttribute(key));if(count<0)throw new IOException("测试报告计数无效");result.put(key,count);
        }
        var failed=new ArrayList<Map<String,String>>();var cases=root.getElementsByTagName("testcase");
        for(int i=0;i<cases.getLength()&&failed.size()<12;i++) {
            var test=(org.w3c.dom.Element)cases.item(i);
            for(String type:List.of("failure","error")) {
                var problems=test.getElementsByTagName(type);
                if(problems.getLength()>0) {
                    var problem=(org.w3c.dom.Element)problems.item(0);
                    failed.add(Map.of("test",clip(test.getAttribute("classname")+"#"+test.getAttribute("name"),240),
                            "type",type,"message",clip(problem.getAttribute("message"),1200),"detail",clip(problem.getTextContent(),1600)));
                }
            }
        }
        result.put("failedTests",failed);return result;
    }
    private static String clip(String value,int limit){return value.length()<=limit?value:value.substring(0,limit)+"…";}
}
