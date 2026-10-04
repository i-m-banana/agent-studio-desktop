package com.agentstudio.conversation;

final class ModelConnectionFailure extends java.io.IOException {
    ModelConnectionFailure(int attempts,Exception cause) {
        super("暂时无法连接模型服务，已尝试 "+attempts+" 次，本次任务未完成。此前工具操作不会自动撤销，也不会重复执行。",cause);
    }
    static boolean retryable(Throwable error) {
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause()) {
            if(error instanceof java.net.http.HttpConnectTimeoutException||error instanceof java.net.ConnectException)return true;
        }
        return false;
    }
}
