/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.unsafe.memory;

import org.apache.spark.unsafe.Platform;

/**
 * A simple {@link MemoryAllocator} that uses {@code Unsafe} to allocate off-heap memory.
 */
public class UnsafeMemoryAllocator implements MemoryAllocator {

  @Override
  public MemoryBlock allocate(long size) throws OutOfMemoryError {
    long address = Platform.allocateMemory(size); // address 是堆外分配的java程序空间的基地址, OS可以访问的进程的地址,堆外无对象头的字节数组。动用了Unsafe, 最底层调用了native 的allocateMemory0
    MemoryBlock memory = new MemoryBlock(null, address, size); // 第一个参数是否为null是Unsafe的开关，决定on/off heap，Platform/Unsafe在putInt的时候会判断这个取值。off heap是Xmx的堆大小，Java除了这个堆，还可以在申请内存空间来用，并不计入堆，Xmx已经限定死了堆的大小，规定new出来的对象总大小小于这个数
    if (MemoryAllocator.MEMORY_DEBUG_FILL_ENABLED) {
      memory.fill(MemoryAllocator.MEMORY_DEBUG_FILL_CLEAN_VALUE);  // 0xa5
    }
    return memory;
  }

  @Override
  public void free(MemoryBlock memory) {
    assert (memory.obj == null) :
      "baseObject not null; are you trying to use the off-heap allocator to free on-heap memory?";
    assert (memory.pageNumber != MemoryBlock.FREED_IN_ALLOCATOR_PAGE_NUMBER) :
      "page has already been freed";
    assert ((memory.pageNumber == MemoryBlock.NO_PAGE_NUMBER)
            || (memory.pageNumber == MemoryBlock.FREED_IN_TMM_PAGE_NUMBER)) :
      "TMM-allocated pages must be freed via TMM.freePage(), not directly in allocator free()";

    if (MemoryAllocator.MEMORY_DEBUG_FILL_ENABLED) {
      memory.fill(MemoryAllocator.MEMORY_DEBUG_FILL_FREED_VALUE);  // 释放的时候是填充0x5a😀😀
    }
    Platform.freeMemory(memory.offset);
    // As an additional layer of defense against use-after-free bugs, we mutate the
    // MemoryBlock to reset its pointer.
    memory.offset = 0;
    // Mark the page as freed (so we can detect double-frees).
    memory.pageNumber = MemoryBlock.FREED_IN_ALLOCATOR_PAGE_NUMBER;
  }
}
