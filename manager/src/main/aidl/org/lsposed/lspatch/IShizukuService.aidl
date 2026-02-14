package org.lsposed.lspatch;

interface IShizukuService {
    // Executes a shell command and returns the output
    String runShellCommand(String cmd) = 1;
    
    // Allows closing the service from the client side
    void destroy() = 2;
}
