package com.github.unidbg.debugger;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.BlockHook;
import com.github.unidbg.arm.backend.DebugHook;

import java.io.PrintStream;

import java.util.Map;

public interface Debugger extends Breaker, DebugHook, BlockHook {

    BreakPoint addBreakPoint(Module module, String symbol);
    BreakPoint addBreakPoint(Module module, String symbol, BreakPointCallback callback);
    BreakPoint addBreakPoint(Module module, long offset);
    BreakPoint addBreakPoint(Module module, long offset, BreakPointCallback callback);

    /**
     * @param address 奇数地址表示thumb断点
     */
    BreakPoint addBreakPoint(long address);
    BreakPoint addBreakPoint(long address, BreakPointCallback callback);

    void traceFunctionCall(FunctionCallListener listener);

    /**
     * use with unicorn
     * @param module <code>null</code> means all modules.
     */
    void traceFunctionCall(Module module, FunctionCallListener listener);

    @SuppressWarnings("unused")
    void setDebugListener(DebugListener listener);

    <T> T run(DebugRunnable<T> runnable) throws Exception;

    boolean hasRunnable();

    boolean isDebugging();

    void disassembleBlock(Emulator<?> emulator, long address, boolean thumb);

    void addMcpTool(String name, String description, String... paramNames);
    /**
     * Start the HTTP MCP endpoint without entering the interactive debugger
     * command loop first.
     *
     * @param port requested TCP port; non-positive values use the default
     *             debugger port
     * @return the port selected for the MCP endpoint
     */
    int startMcpServer(int port);
    /**
     * Start the line-oriented MCP stdio endpoint. The supplied stream remains
     * reserved for JSON-RPC responses; diagnostics use the process diagnostic
     * stream selected by the caller.
     *
     * @param output MCP JSON-RPC output stream
     */
    void startMcpStdioServer(PrintStream output);

    boolean removeBreakPoint(long address);

    Map<Long, BreakPoint> getBreakPoints();

    void close();

}
