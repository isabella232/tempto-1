/*
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
package io.prestodb.tempto.threads;

import com.google.common.base.Joiner;

import java.util.List;

public class ParallelExecutionException
        extends RuntimeException
{
    private final List<Throwable> throwables;

    public ParallelExecutionException(List<Throwable> throwables)
    {
        super("Throwables when running parallel runnables:\n" + Joiner.on("-------------------\n").join(throwables));
        this.throwables = throwables;
    }

    public List<Throwable> getThrowables()
    {
        return throwables;
    }
}