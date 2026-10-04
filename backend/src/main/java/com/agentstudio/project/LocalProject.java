package com.agentstudio.project;

import java.util.List;

public record LocalProject(String id, String name, String sourceRoot, List<String> writableDirectories,
                           List<String> protectedDirectories, int revision, boolean archived, boolean protectionConfirmed) {}
