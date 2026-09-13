package com.agentstudio.secret;

import java.util.List;

public record SecretStatus(String name, boolean configured, String source, List<String> usedBy) {}
