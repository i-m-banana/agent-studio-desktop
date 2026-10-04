package com.agentstudio.coding;

/** Keep startup context and final diagnostics while draining the entire process stream. */
final class BoundedProcessOutput {
    private final int headLimit, tailLimit;
    private final StringBuilder head = new StringBuilder(), tail = new StringBuilder();
    private long length;
    BoundedProcessOutput(int headLimit, int tailLimit) { this.headLimit=headLimit;this.tailLimit=tailLimit; }
    synchronized void append(char[] chars,int count) {
        length+=count;
        int first=Math.min(count,headLimit-head.length());
        head.append(chars,0,first);
        if(count>first) {
            tail.append(chars,first,count-first);
            if(tail.length()>tailLimit)tail.delete(0,tail.length()-tailLimit);
        }
    }
    synchronized boolean truncated(){return length>headLimit+tailLimit;}
    synchronized String text(){return head+(truncated()?"\n[日志过长，已省略中间内容；以下为末尾诊断]\n":"")+tail;}
}
