/*
 * Copyright 2015 Hippo Seven
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

package com.hippo.io;

import android.support.annotation.NonNull;

import com.hippo.unifile.UniFile;
import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.IOException;
import java.io.InputStream;

public class UniFileInputStreamPipe implements InputStreamPipe {

    private UniFile mUniFile;

    private InputStream mIs;

    public UniFileInputStreamPipe(UniFile uniFile) {
        mUniFile = uniFile;
    }

    @Override
    public void obtain() {
        // Empty
    }

    @Override
    public void release() {
        // Empty
    }

    @Override
    public @NonNull InputStream open() throws IOException {
        if (mIs != null) {
            throw new IllegalStateException("Please close it first");
        }

        mIs = mUniFile.openInputStream();
        return mIs;
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(mIs);
        mIs = null;
    }
}