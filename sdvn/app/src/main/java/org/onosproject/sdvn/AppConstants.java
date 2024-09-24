/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.sdvn;

import org.onosproject.net.pi.model.PiPipeconfId;

public final class AppConstants {

    // Private constructor to prevent instantiation
    private AppConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static final String APP_NAME = "org.onosproject.sdvn";
    public static final PiPipeconfId PIPECONF_ID = new PiPipeconfId("org.onosproject.pipelines.sdvn");

    public static final int DEFAULT_FLOW_RULE_PRIORITY = 10;
    public static final int INITIAL_SETUP_DELAY = 2; // Seconds.
    public static final int CLEAN_UP_DELAY = 2000; // milliseconds
    public static final int DEFAULT_CLEAN_UP_RETRY_TIMES = 10;

    public static final int CPU_PORT_ID = 200;
    public static final int CPU_CLONE_SESSION_ID = 99;
}
