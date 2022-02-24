/*
 * Copyright 2022 Netflix, Inc.
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

package io.mantisrx.server.worker;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;

@RequiredArgsConstructor
class PrefixedBlobStore implements BlobStore {
  private final URI rootUri;
  private final BlobStore blobStore;

  @Override
  public File get(URI blobUrl) throws IOException {
    final String fileName = FilenameUtils.getName(blobUrl.toString());
    return blobStore.get(rootUri.resolve(fileName));
  }

  @Override
  public void close() throws IOException {
    blobStore.close();
  }
}