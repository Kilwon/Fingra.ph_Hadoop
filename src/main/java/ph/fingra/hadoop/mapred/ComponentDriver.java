/**
 * Copyright 2014 tgrape Inc.
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

package ph.fingra.hadoop.mapred;

import org.apache.hadoop.util.ProgramDriver;

import ph.fingra.hadoop.common.logger.ErrorLogger;
import ph.fingra.hadoop.mapred.parts.component.ComponentNewuserStatistic;

public class ComponentDriver {

    public static void main(String argv[]) {
        
        int exitcode = -1;
        
        ProgramDriver pgd = new ProgramDriver();
        try {
            
            pgd.addClass("componentnewuser", ComponentNewuserStatistic.class,
                    "Fingraph OSS map/reduce program for component/componentnewuser");
            
            pgd.driver(argv);
            
            // seccess
            exitcode = 0;
        }
        catch(Throwable e) {
            ErrorLogger.log(e.toString());
        }
        
        System.exit(exitcode);
    }
}
