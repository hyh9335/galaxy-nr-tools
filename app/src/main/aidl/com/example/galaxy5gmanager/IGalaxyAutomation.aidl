package com.example.galaxy5gmanager;
interface IGalaxyAutomation {
    int shellUid();
    boolean startRun(String script, String mode, String profile, String confirmation);
    boolean isRunning();
    String getReport();
    void cancel();
    void destroy() = 16777114;
}