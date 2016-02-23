/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the “License”). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file.options;

import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Tests for the {@link ListStatusOptions} class.
 */
public class ListStatusOptionsTest {
  /**
   * Tests that building a {@link ListStatusOptions} with the defaults works.
   */
  @Test
  public void defaultsTest() {
    ListStatusOptions.defaults();
  }

  /**
   * Tests getting and setting fields.
   */
  @Test
  public void fieldsTest() {
    Random random = new Random();
    boolean checkUfs = random.nextBoolean();

    ListStatusOptions options = ListStatusOptions.defaults();
    options.setCheckUfs(checkUfs);

    Assert.assertEquals(checkUfs, options.isCheckUfs());
  }

}
