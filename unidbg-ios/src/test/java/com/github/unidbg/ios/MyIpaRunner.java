package com.github.unidbg.ios;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.HypervisorFactory;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.debugger.McpTool;
import com.github.unidbg.debugger.McpToolkit;
import com.github.unidbg.ios.objc.ObjC;
import com.github.unidbg.ios.struct.objc.ObjcClass;
import com.github.unidbg.ios.struct.objc.ObjcObject;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.unix.FileListener;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.hook.HookLoader;
import com.github.unidbg.hook.MsgSendCallback;
import com.sun.jna.Pointer;
import com.github.unidbg.ios.ipa.EmulatorConfigurator;
import com.github.unidbg.ios.ipa.IpaLoader;
import com.github.unidbg.ios.ipa.IpaLoader64;
import com.github.unidbg.ios.ipa.LoadedIpa;

import java.io.File;

public class MyIpaRunner implements EmulatorConfigurator {

    private Emulator<DarwinFileIO> emulator;
    private Module module;

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.err.println(
                "Usage: MyIpaRunner /path/to/decrypted.ipa"
            );
            System.exit(1);
        }

        new MyIpaRunner().run(new File(args[0]));
    }

    private void run(File ipa) throws Exception {

        if (!ipa.isFile()) {
            throw new IllegalArgumentException(
                "IPA not found: " + ipa
            );
        }

        File rootfs = new File(
            "target/rootfs/my-ios-app"
        );

        IpaLoader loader =
            new IpaLoader64(ipa, rootfs);

        loader.useOverrideResolver();

        String backend = System.getProperty(
            "unidbg.backend",
            "auto"
        );

        if ("unicorn2".equalsIgnoreCase(backend) ||
                "unicorn".equalsIgnoreCase(backend)) {
            loader.addBackendFactory(
                new Unicorn2Factory(true)
            );
        } else if (!"dynarmic".equalsIgnoreCase(backend)) {
            /*
             * Apple Silicon:
             * try Hypervisor first.
             */
            loader.addBackendFactory(
                new HypervisorFactory(true)
            );
        }

        /*
         * Fallback backend.
         */
        loader.addBackendFactory(
            new DynarmicFactory(true)
        );

        LoadedIpa loaded =
            loader.load(this);

        emulator =
            loaded.getEmulator();

        module =
            loaded.getExecutable();

        System.out.println(
            "Executable: " + module.name
        );

        System.out.printf(
            "Base: 0x%x%n",
            module.base
        );

        Debugger debugger =
            emulator.attach();

        long breakOffset =
            Long.decode(
                System.getProperty("unidbg.breakOffset", "0x6a5c")
            );
        long entryAddress =
            ((MachOModule) module).machHeader + breakOffset;

        /*
         * Add a configurable Runner breakpoint before invoking the IPA.
         */
        if (Boolean.parseBoolean(
                System.getProperty("unidbg.breakAtEntry", "true"))) {
            debugger.addBreakPoint(entryAddress);
            System.out.printf(
                "Entry breakpoint: 0x%x (offset 0x%x)%n",
                entryAddress,
                breakOffset
            );
        }

        if (Boolean.parseBoolean(
                System.getProperty("unidbg.saromTool", "false"))) {
            final ObjC objc = ObjC.getInstance(emulator);
            McpToolkit toolkit = new McpToolkit();
            toolkit.addTool(new McpTool() {
                @Override
                public String name() {
                    return "saromIdle";
                }

                @Override
                public String description() {
                    return "Keep the repeatable SAROM debugger idle until a probe is requested";
                }

                @Override
                public String[] paramNames() {
                    return new String[0];
                }

                @Override
                public void execute(String[] params) {
                    System.out.println("SAROM debugger ready");
                }
            }).addTool(new McpTool() {
                @Override
                public String name() {
                    return "invokeSarom";
                }

                @Override
                public String description() {
                    return "Invoke NSData dataFromSecureAppROMItem: for a supplied item";
                }

                @Override
                public String[] paramNames() {
                    return new String[]{"item"};
                }

                @Override
                public void execute(String[] params) {
                    String itemName = params.length > 0 ? params[0] : "";
                    ObjcClass dataClass = objc.getClass("NSData");
                    com.github.unidbg.ios.objc.NSString item = objc.newString(itemName);
                    Number result = objc.msgSend(
                            emulator,
                            dataClass,
                            objc.registerName("dataFromSecureAppROMItem:"),
                            item
                    );
                    System.out.printf(
                            "SAROM item=%s result=0x%x%n",
                            itemName,
                            result.longValue()
                    );
                }
            }).addTool(new McpTool() {
                @Override
                public String name() {
                    return "invokeObjc";
                }

                @Override
                public String description() {
                    return "Invoke an Objective-C class method with an optional NSString argument";
                }

                @Override
                public String[] paramNames() {
                    return new String[]{"class", "selector", "arg"};
                }

                @Override
                public void execute(String[] params) {
                    String className = params.length > 0 ? params[0] : "";
                    String selector = params.length > 1 ? params[1] : "";
                    ObjcClass cls = objc.getClass(className);
                    Number result;
                    if (params.length > 2 && !params[2].isEmpty()) {
                        result = objc.msgSend(
                                emulator,
                                cls,
                                objc.registerName(selector),
                                objc.newString(params[2])
                        );
                    } else {
                        result = objc.msgSend(
                                emulator,
                                cls,
                                objc.registerName(selector)
                        );
                    }
                    System.out.printf(
                            "OBJC CALL class=%s selector=%s result=0x%x%n",
                            className,
                            selector,
                            result.longValue()
                    );
                }
            }).addTool(new McpTool() {
                @Override
                public String name() {
                    return "invokeObjcReceiver";
                }

                @Override
                public String description() {
                    return "Invoke an Objective-C instance method by receiver address";
                }

                @Override
                public String[] paramNames() {
                    return new String[]{"receiver", "selector"};
                }

                @Override
                public void execute(String[] params) {
                    long receiver = Long.decode(params[0]);
                    Number result = objc.msgSend(
                            emulator,
                            UnidbgPointer.pointer(emulator, receiver),
                            objc.registerName(params[1])
                    );
                    System.out.printf(
                            "OBJC CALL receiver=0x%x selector=%s result=0x%x%n",
                            receiver,
                            params[1],
                            result.longValue()
                    );
                }
            }).addTool(new McpTool() {
                @Override
                public String name() {
                    return "invokeProbe";
                }

                @Override
                public String description() {
                    return "Invoke a no-argument bpddiyx function by image-relative offset";
                }

                @Override
                public String[] paramNames() {
                    return new String[]{"offset"};
                }

                @Override
                public void execute(String[] params) {
                    String offsetText = params.length > 0 && !params[0].isEmpty()
                            ? params[0]
                            : System.getProperty("unidbg.probeOffset", "0x696400");
                    Module target = emulator.getMemory().findModule("bpddiyx");
                    if (target == null) {
                        throw new IllegalStateException("bpddiyx module is not loaded");
                    }
                    long offset = Long.decode(offsetText);
                    long address = target.base + offset;
                    Number result = Module.emulateFunction(emulator, address);
                    System.out.printf(
                            "PROBE offset=%s address=0x%x result=0x%x%n",
                            offsetText,
                            address,
                            result.longValue()
                    );
                }
            }).addTool(new McpTool() {
                @Override
                public String name() {
                    return "invokeFunction";
                }

                @Override
                public String description() {
                    return "Invoke a bpddiyx function by image-relative offset with up to four integer arguments";
                }

                @Override
                public String[] paramNames() {
                    return new String[]{"offset", "arg0", "arg1", "arg2", "arg3"};
                }

                @Override
                public void execute(String[] params) {
                    String offsetText = params.length > 0 && !params[0].isEmpty()
                            ? params[0]
                            : "0";
                    Module target = emulator.getMemory().findModule("bpddiyx");
                    if (target == null) {
                        throw new IllegalStateException("bpddiyx module is not loaded");
                    }
                    long offset = Long.decode(offsetText);
                    Object[] args = new Object[Math.max(0, params.length - 1)];
                    for (int i = 1; i < params.length; i++) {
                        if (params[i] != null && !params[i].isEmpty()) {
                            args[i - 1] = Long.decode(params[i]);
                        } else {
                            args[i - 1] = 0L;
                        }
                    }
                    long address = target.base + offset;
                    Number result = Module.emulateFunction(emulator, address, args);
                    System.out.printf(
                            "CALL offset=%s address=0x%x argc=%d result=0x%x%n",
                            offsetText,
                            address,
                            args.length,
                            result.longValue()
                    );
                }
            });
            toolkit.addTool(new McpTool() {
                @Override
                public String name() {
                    return "runEntry";
                }

                @Override
                public String description() {
                    return "Run the IPA entry point and observe native resource loads";
                }

                @Override
                public String[] paramNames() {
                    return new String[0];
                }

                @Override
                public void execute(String[] params) {
                    System.out.println("Starting IPA entry point");
                    loaded.callEntry();
                }
            });
            toolkit.run(debugger);
            emulator.close();
            return;
        }

        /*
         * Enter the debugger loop before callEntry so MCP can configure
         * native breakpoints without restarting the main task.
         */
        if (Boolean.parseBoolean(
                System.getProperty("unidbg.pauseBeforeEntry", "false"))) {
            System.out.println(
                "Paused before IPA entry point; start MCP with `mcp <port>`."
            );
            debugger.onBreak(
                emulator.getBackend(),
                entryAddress,
                4,
                emulator
            );
        }

        if (Boolean.parseBoolean(System.getProperty("unidbg.callEntry", "true"))) {
            System.out.println(
                "Starting IPA entry point..."
            );
            loaded.callEntry();
        } else {
            System.out.println(
                "IPA loaded; entry point skipped."
            );
        }

        emulator.close();
    }

    @Override
    public void configure(
            Emulator<DarwinFileIO> emulator,
            String executableBundlePath,
            File rootDir,
            String bundleIdentifier) {

        if (!Boolean.parseBoolean(System.getProperty("unidbg.callInit", "true"))) {
            ((MachOLoader) emulator.getMemory()).disableCallInitFunction();
        }
        if (Boolean.parseBoolean(System.getProperty("unidbg.patchSaromExit", "false"))) {
            ((MachOLoader) emulator.getMemory()).addModuleListener((emu, module) -> {
                if (module.name.contains("bpddiyx")) {
                    long address = module.base + 0x96f190;
                    UnidbgPointer.pointer(emu, address).setInt(0, 0xd503201f);
                    System.out.printf(
                            "Patched initializer trap at 0x%x%n",
                            address
                    );
                }
            });
        }
        if (Boolean.parseBoolean(System.getProperty("unidbg.traceFiles", "false"))) {
            emulator.getSyscallHandler().setFileListener(new FileListener() {
                @Override
                public void onOpenSuccess(
                        Emulator<?> emu,
                        String pathname,
                        FileIO io) {
                    System.out.println("FILE OPEN " + pathname + " [" + io + "]");
                }

                @Override
                public void onRead(
                        Emulator<?> emu,
                        String pathname,
                        byte[] bytes) {
                    System.out.println("FILE READ " + pathname + " bytes=" + bytes.length);
                }

                @Override
                public void onWrite(
                        Emulator<?> emu,
                        String pathname,
                        byte[] bytes) {
                    System.out.println("FILE WRITE " + pathname + " bytes=" + bytes.length);
                }

                @Override
                public void onClose(
                        Emulator<?> emu,
                        FileIO io) {
                    System.out.println("FILE CLOSE [" + io + "]");
                }
            });
        }

        System.out.println(
            "Bundle ID: " +
            bundleIdentifier
        );

        System.out.println(
            "Executable path: " +
            executableBundlePath
        );

        System.out.println(
            "RootFS: " +
            rootDir
        );
    }


    @Override
    public void onExecutableLoaded(
            Emulator<DarwinFileIO> emulator,
            MachOModule executable) {

        if (Boolean.parseBoolean(System.getProperty("unidbg.traceObjc", "false"))) {
            HookLoader.load(emulator).hookObjcMsgSend(
                    (emu, systemClass, className, cmd, lr) -> {
                        boolean browserCall =
                                "openURL:".equals(cmd) ||
                                "openURL:options:".equals(cmd) ||
                                "openURL:options:completionHandler:".equals(cmd) ||
                                "canOpenURL:".equals(cmd) ||
                                "initWithURL:".equals(cmd) ||
                                "loadRequest:".equals(cmd) ||
                                "loadHTMLString:baseURL:".equals(cmd) ||
                                "presentViewController:animated:completion:".equals(cmd);
                        boolean resourceCall =
                                "dataFromSecureAppROMItem:".equals(cmd) ||
                                "pathForResource:ofType:".equals(cmd) ||
                                "inputStreamWithFileAtPath:".equals(cmd);
                        if (!browserCall && !resourceCall) {
                            return false;
                        }
                        System.out.printf(
                                "%s MSG class=%s selector=%s lr=0x%x%n",
                                browserCall ? "BROWSER" : "OBJC",
                                className,
                                cmd,
                                lr == null ? 0 : Pointer.nativeValue(lr)
                        );
                        return false;
                    }
            );
        }

        System.out.printf(
            "Mach-O loaded: %s @ 0x%x%n",
            executable.name,
            executable.base
        );
    }
}