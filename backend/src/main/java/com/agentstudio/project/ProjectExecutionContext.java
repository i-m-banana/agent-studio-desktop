package com.agentstudio.project;

/** Server-owned binding. Never populated from model tool arguments. */
public final class ProjectExecutionContext {
    private static final ThreadLocal<LocalProject> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> SOURCE_SHA = new ThreadLocal<>();
    private ProjectExecutionContext() {}
    public static LocalProject current() { return CURRENT.get(); }
    public static String sourceSha256() { return SOURCE_SHA.get(); }
    public static void bindSourceSha256(String value) { if(value==null) SOURCE_SHA.remove(); else SOURCE_SHA.set(value); }
    public static AutoCloseable enter(LocalProject project) {
        return enter(project,null);
    }
    public static AutoCloseable enter(LocalProject project,String sha) {
        var previous = CURRENT.get();
        var previousSha=SOURCE_SHA.get(); bindSourceSha256(sha);
        if (project == null) CURRENT.remove(); else CURRENT.set(project);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); bindSourceSha256(previousSha); };
    }
}
