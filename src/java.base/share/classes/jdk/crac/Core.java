// Copyright 2017-2019 Azul Systems, Inc.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
// this list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
// this list of conditions and the following disclaimer in the documentation
// and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package jdk.crac;

import jdk.crac.impl.OrderedContext;

public class Core {
    private static final int JVM_CHECKPOINT_OK    = 0;
    private static final int JVM_CHECKPOINT_ERROR = 1;
    private static final int JVM_CHECKPOINT_NONE  = 2;

    private static final int JVM_CR_FAIL = 0;
    private static final int JVM_CR_FAIL_FILE = 1;
    private static final int JVM_CR_FAIL_SOCK = 2;
    private static final int JVM_CR_FAIL_PIPE = 3;

    private static native Object[] tryCheckpointRestore0();

    private static final Context<Resource> globalContext = new OrderedContext();
    static {
        // force JDK context initialization
        jdk.internal.crac.Core.getJDKContext();
    }

    private static void translateJVMExceptions(int[] codes, String[] messages,
                                               CheckpointException newException) {
        assert codes.length == messages.length;
        final int length = codes.length;

        for (int i = 0; i < length; ++i) {
            switch(codes[i]) {
                case JVM_CR_FAIL_FILE:
                    newException.addSuppressed(
                            new CheckpointOpenFileException(messages[i]));
                    break;
                case JVM_CR_FAIL_SOCK:
                    newException.addSuppressed(
                            new CheckpointOpenSocketException(messages[i]));
                    break;
                case JVM_CR_FAIL_PIPE:
                    // FALLTHROUGH
                default:
                    newException.addSuppressed(
                            new CheckpointOpenResourceException(messages[i]));
                    break;
            }
        }
    }

    public static Context<Resource> getGlobalContext() {
        return globalContext;
    }

    public static void tryCheckpointRestore() throws
            CheckpointException,
            RestoreException {

        try {
            globalContext.beforeCheckpoint(null);
        } catch (CheckpointException ce) {
            try {
                globalContext.afterRestore(null);
            } catch (RestoreException re) {
                CheckpointException newException = new CheckpointException();
                for (Throwable t : ce.getSuppressed()) {
                    newException.addSuppressed(t);
                }
                for (Throwable t : re.getSuppressed()) {
                    newException.addSuppressed(t);
                }
                throw newException;
            }
            throw ce;
        }

        final Object[] bundle = tryCheckpointRestore0();
        final int retCode = (Integer)bundle[0];
        final int[] codes = (int[])bundle[1];
        final String[] messages = (String[])bundle[2];

        if (retCode != JVM_CHECKPOINT_OK) {
            CheckpointException newException = new CheckpointException();
            switch (retCode) {
                case JVM_CHECKPOINT_ERROR:
                    translateJVMExceptions(codes, messages, newException);
                    break;
                case JVM_CHECKPOINT_NONE:
                    newException.addSuppressed(
                            new RuntimeException("C/R is not configured"));
                    break;
                default:
                    newException.addSuppressed(
                            new RuntimeException("Unknown C/R result: " + retCode));
            }

            try {
                globalContext.afterRestore(null);
            } catch (RestoreException re) {
                for (Throwable t : re.getSuppressed()) {
                    newException.addSuppressed(t);
                }
            }
            throw newException;
        }

        globalContext.afterRestore(null);
    }
}
