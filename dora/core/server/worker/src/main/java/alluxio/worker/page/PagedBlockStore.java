/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.page;

import static alluxio.worker.page.PagedBlockStoreMeta.DEFAULT_MEDIUM;
import static alluxio.worker.page.PagedBlockStoreMeta.DEFAULT_TIER;

import alluxio.client.file.cache.CacheManager;
import alluxio.client.file.cache.CacheManagerOptions;
import alluxio.client.file.cache.PageId;
import alluxio.client.file.cache.PageInfo;
import alluxio.client.file.cache.store.PageStoreDir;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.exception.BlockAlreadyExistsException;
import alluxio.exception.BlockDoesNotExistException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.InvalidWorkerStateException;
import alluxio.exception.PageNotFoundException;
import alluxio.exception.runtime.AlluxioRuntimeException;
import alluxio.exception.runtime.AlreadyExistsRuntimeException;
import alluxio.exception.runtime.BlockDoesNotExistRuntimeException;
import alluxio.exception.runtime.NotFoundRuntimeException;
import alluxio.exception.status.DeadlineExceededException;
import alluxio.grpc.Block;
import alluxio.grpc.BlockStatus;
import alluxio.grpc.ErrorType;
import alluxio.grpc.UfsReadOptions;
import alluxio.proto.dataserver.Protocol;
import alluxio.resource.LockResource;
import alluxio.underfs.UfsInputStreamCache;
import alluxio.underfs.UfsManager;
import alluxio.worker.block.AllocateOptions;
import alluxio.worker.block.BlockLock;
import alluxio.worker.block.BlockLockManager;
import alluxio.worker.block.BlockLockType;
import alluxio.worker.block.BlockMasterClient;
import alluxio.worker.block.BlockMasterClientPool;
import alluxio.worker.block.BlockStore;
import alluxio.worker.block.BlockStoreEventListener;
import alluxio.worker.block.BlockStoreLocation;
import alluxio.worker.block.BlockStoreMeta;
import alluxio.worker.block.CreateBlockOptions;
import alluxio.worker.block.io.BlockReader;
import alluxio.worker.block.io.BlockWriter;
import alluxio.worker.block.io.DelegatingBlockReader;
import alluxio.worker.block.io.LocalFileBlockReader;
import alluxio.worker.block.meta.BlockMeta;
import alluxio.worker.block.meta.TempBlockMeta;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.inject.Named;

/**
 * A paged implementation of LocalBlockStore interface.
 * Implements the block level operations， but instead of using physical block files,
 * we use pages managed by the CacheManager to store the data.
 */
public class PagedBlockStore implements BlockStore {
  private static final Logger LOG = LoggerFactory.getLogger(PagedBlockStore.class);

  private final CacheManager mCacheManager;
  private final UfsManager mUfsManager;

  private final BlockLockManager mLockManager = new BlockLockManager();
  private final PagedBlockMetaStore mPageMetaStore;
  private final BlockMasterClientPool mBlockMasterClientPool;
  private final AtomicReference<Long> mWorkerId;
  /** A set of pinned inodes updated via periodic master-worker sync. */
  private final Set<Long> mPinnedInodes = new HashSet<>();
  private final UfsInputStreamCache mUfsInStreamCache = new UfsInputStreamCache();
  private final List<BlockStoreEventListener> mBlockStoreEventListeners =
      new CopyOnWriteArrayList<>();
  private final long mPageSize;
  private static final Long REMOVE_BLOCK_TIMEOUT_MS = 60_000L;

  /** Lock to guard metadata operations. */
  private final ReentrantReadWriteLock mMetadataLock = new ReentrantReadWriteLock();

  /** ReadLock provided by {@link #mMetadataLock} to guard metadata read operations. */
  private final Lock mMetadataReadLock = mMetadataLock.readLock();

  /** WriteLock provided by {@link #mMetadataLock} to guard metadata write operations. */
  private final Lock mMetadataWriteLock = mMetadataLock.writeLock();

  /**
   * Create an instance of PagedBlockStore.
   * @param ufsManager the UFS manager
   * @param pool a client pool for talking to the block master
   * @param workerId the worker id
   * @return an instance of PagedBlockStore
   */
  public static PagedBlockStore create(UfsManager ufsManager, BlockMasterClientPool pool,
      AtomicReference<Long> workerId) {
    try {
      AlluxioConfiguration conf = Configuration.global();
      CacheManagerOptions cacheManagerOptions = CacheManagerOptions.createForWorker(conf);
      List<PageStoreDir> pageStoreDirs = PageStoreDir.createPageStoreDirs(cacheManagerOptions);
      List<PagedBlockStoreDir> dirs = PagedBlockStoreDir.fromPageStoreDirs(pageStoreDirs);
      PagedBlockMetaStore pageMetaStore = new PagedBlockMetaStore(dirs);
      CacheManager cacheManager =
          CacheManager.Factory.create(conf, cacheManagerOptions, pageMetaStore);
      return new PagedBlockStore(cacheManager, ufsManager, pool, workerId, pageMetaStore,
          cacheManagerOptions.getPageSize());
    } catch (IOException e) {
      throw new RuntimeException("Failed to create PagedLocalBlockStore", e);
    }
  }

  /**
   * Constructor for PagedLocalBlockStore.
   *
   * @param cacheManager page cache manager
   * @param ufsManager ufs manager
   * @param pageMetaStore meta data store for pages and blocks
   * @param pageSize page size
   */
  @Inject
  PagedBlockStore(CacheManager cacheManager, UfsManager ufsManager, BlockMasterClientPool pool,
                  @Named("workerId") AtomicReference<Long> workerId,
                  PagedBlockMetaStore pageMetaStore,
                  @Named("pageSize") Long pageSize) {
    mCacheManager = cacheManager;
    mUfsManager = ufsManager;
    mBlockMasterClientPool = pool;
    mWorkerId = workerId;
    mPageMetaStore = pageMetaStore;
    mPageSize = pageSize;
  }

  @Override
  public Optional<BlockLock> pinBlock(long sessionId, long blockId) {
    LOG.debug("pinBlock: sessionId={}, blockId={}", sessionId, blockId);
    BlockLock lock = mLockManager.acquireBlockLock(sessionId, blockId, BlockLockType.READ);
    if (hasBlockMeta(blockId)) {
      return Optional.of(lock);
    }
    lock.close();
    return Optional.empty();
  }

  @Override
  public void unpinBlock(BlockLock lock) {
    LOG.debug("unpinBlock: id={}", lock.get());
    lock.close();
  }

  @Override
  public void commitBlock(long sessionId, long blockId, boolean pinOnCreate) {
    LOG.debug("commitBlock: sessionId={}, blockId={}, pinOnCreate={}",
        sessionId, blockId, pinOnCreate);
    try (BlockLock blockLock =
             mLockManager.acquireBlockLock(sessionId, blockId, BlockLockType.WRITE);
         LockResource metaLock = new LockResource(mPageMetaStore.getLock().writeLock())) {
      PagedBlockMeta blockMeta = mPageMetaStore.getTempBlock(blockId)
          .orElseThrow(() -> new BlockDoesNotExistRuntimeException(blockId));
      Preconditions.checkState(
          blockMeta.getBlockSize() == blockMeta.getDir().getTempBlockCachedBytes(blockId),
          "committing a block which has not been not fully written"
      );
      PagedBlockStoreDir pageStoreDir = blockMeta.getDir();
      // unconditionally pin this block until committing is done
      boolean isPreviouslyUnpinned = pageStoreDir.getEvictor().addPinnedBlock(blockId);
      try {
        pageStoreDir.commit(BlockPageId.tempFileIdOf(blockId),
            BlockPageId.fileIdOf(blockId, blockMeta.getBlockSize()));
        final PagedBlockMeta committed = mPageMetaStore.commit(blockId);
        BlockStoreLocation blockLocation =
                new BlockStoreLocation(DEFAULT_TIER, getDirIndexOfBlock(blockId));
        for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
          synchronized (listener) {
            listener.onCommitBlockToLocal(blockId, blockLocation);
          }
        }
        commitBlockToMaster(committed);
        for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
          synchronized (listener) {
            listener.onCommitBlockToMaster(blockId, blockLocation);
          }
        }
      } catch (IOException e) {
        throw AlluxioRuntimeException.from(e);
      } finally {
        if (!pinOnCreate && isPreviouslyUnpinned) {
          pageStoreDir.getEvictor().removePinnedBlock(blockId);
        }
      }
    }
  }

  /**
   * Commits a block to master. The block must have been committed in metastore and storage dir.
   * Caller must have acquired at least READ lock on the metastore and the block.
   * @param blockMeta the block to commit
   */
  private void commitBlockToMaster(PagedBlockMeta blockMeta) {
    final long blockId = blockMeta.getBlockId();
    BlockMasterClient bmc = mBlockMasterClientPool.acquire();
    try {
      bmc.commitBlock(mWorkerId.get(), mPageMetaStore.getStoreMeta().getUsedBytes(), DEFAULT_TIER,
          DEFAULT_MEDIUM, blockId, blockMeta.getBlockSize());
    } catch (IOException e) {
      throw new AlluxioRuntimeException(Status.UNAVAILABLE,
          ExceptionMessage.FAILED_COMMIT_BLOCK_TO_MASTER.getMessage(blockId), e, ErrorType.Internal,
          false);
    } finally {
      mBlockMasterClientPool.release(bmc);
    }
  }

  @Override
  public String createBlock(long sessionId, long blockId, int tier,
      CreateBlockOptions createBlockOptions) {
    String fileId = BlockPageId.tempFileIdOf(blockId);
    PageStoreDir pageStoreDir =
        mPageMetaStore.allocate(fileId, createBlockOptions.getInitialBytes());
    pageStoreDir.putTempFile(fileId);
    return "DUMMY_FILE_PATH";
  }

  @Override
  public BlockReader createBlockReader(long sessionId, long blockId, long offset,
                                       boolean positionShort, Protocol.OpenUfsBlockOptions options)
      throws IOException {
    BlockLock blockLock = mLockManager.acquireBlockLock(sessionId, blockId, BlockLockType.READ);

    try (LockResource lock = new LockResource(mPageMetaStore.getLock().readLock())) {
      Optional<PagedBlockMeta> blockMeta = mPageMetaStore.getBlock(blockId);
      if (blockMeta.isPresent()) {
        final BlockPageEvictor evictor = blockMeta.get().getDir().getEvictor();
        evictor.addPinnedBlock(blockId);
        return new DelegatingBlockReader(getBlockReader(blockMeta.get(), offset, options), () -> {
          evictor.removePinnedBlock(blockId);
          unpinBlock(blockLock);
        });
      }
    }
    // this is a block that needs to be read from UFS
    try (LockResource lock = new LockResource(mPageMetaStore.getLock().writeLock())) {
      // in case someone else has added this block while we wait for the lock,
      // just use the block meta; otherwise create a new one and add to the metastore
      Optional<PagedBlockMeta> blockMeta = mPageMetaStore.getBlock(blockId);
      if (blockMeta.isPresent()) {
        blockMeta.get().getDir().getEvictor().addPinnedBlock(blockId);
        return new DelegatingBlockReader(getBlockReader(blockMeta.get(), offset, options), () -> {
          blockMeta.get().getDir().getEvictor().removePinnedBlock(blockId);
          unpinBlock(blockLock);
        });
      }
      long blockSize = options.getBlockSize();
      PagedBlockStoreDir dir =
          (PagedBlockStoreDir) mPageMetaStore.allocate(BlockPageId.fileIdOf(blockId, blockSize),
              blockSize);
      PagedBlockMeta newBlockMeta = new PagedBlockMeta(blockId, blockSize, dir);
      if (options.getNoCache()) {
        // block does not need to be cached in Alluxio, no need to add and commit it
        unpinBlock(blockLock);
        final UfsBlockReadOptions readOptions;
        try {
          readOptions = UfsBlockReadOptions.fromProto(options);
        } catch (IllegalArgumentException e) {
          throw new AlluxioRuntimeException(Status.INTERNAL,
              String.format("Block %d may need to be read from UFS, but key UFS read options "
                  + "is missing in client request", blockId), e, ErrorType.Internal, false);
        }
        return new PagedUfsBlockReader(mUfsManager, mUfsInStreamCache, newBlockMeta,
            offset, readOptions, mPageSize);
      }
      mPageMetaStore.addBlock(newBlockMeta);
      dir.getEvictor().addPinnedBlock(blockId);
      return new DelegatingBlockReader(getBlockReader(newBlockMeta, offset, options), () -> {
        commitBlockToMaster(newBlockMeta);
        newBlockMeta.getDir().getEvictor().removePinnedBlock(blockId);
        unpinBlock(blockLock);
      });
    }
  }

  @Override
  public BlockReader createBlockReader(long sessionId, long blockId, long lockId)
      throws BlockDoesNotExistException, IOException {
    LOG.debug("getBlockReader: sessionId={}, blockId={}, lockId={}", sessionId, blockId, lockId);
    try (LockResource r = new LockResource(mMetadataReadLock)) {
      BlockMeta blockMeta = mPageMetaStore.getBlock(blockId).get();
      return new LocalFileBlockReader(blockMeta.getPath());
    }
  }

  private BlockReader getBlockReader(PagedBlockMeta blockMeta, long offset,
      Protocol.OpenUfsBlockOptions options) {
    final long blockId = blockMeta.getBlockId();
    Optional<UfsBlockReadOptions> readOptions = Optional.empty();
    try {
      readOptions = Optional.of(UfsBlockReadOptions.fromProto(options));
    } catch (IllegalArgumentException e) {
      // the client does not provide enough information about how to read this block from UFS
      // this is fine for e.g. MUST_CACHE files, so we will simply ignore the error here
      // on the other hand, if the block being read should be readable from UFS, but
      // somehow client didn't send the read options, we will raise the error in the block reader
      // when encountered
      LOG.debug("Client did not provide enough info to read block {} from UFS", blockId, e);
    }
    final Optional<PagedUfsBlockReader> ufsBlockReader =
        readOptions.map(opt -> new PagedUfsBlockReader(
            mUfsManager, mUfsInStreamCache, blockMeta, offset, opt, mPageSize));
    return new PagedBlockReader(mCacheManager, blockMeta, offset, ufsBlockReader, mPageSize);
  }

  @Override
  public BlockReader createUfsBlockReader(long sessionId, long blockId, long offset,
                                          boolean positionShort,
                                          Protocol.OpenUfsBlockOptions options) throws IOException {
    PagedBlockMeta blockMeta = mPageMetaStore
        .getBlock(blockId)
        .orElseGet(() -> {
          long blockSize = options.getBlockSize();
          PagedBlockStoreDir dir =
              (PagedBlockStoreDir) mPageMetaStore.allocate(String.valueOf(blockId), blockSize);
          // do not add the block to metastore
          return new PagedBlockMeta(blockId, blockSize, dir);
        });
    UfsBlockReadOptions readOptions = UfsBlockReadOptions.fromProto(options);
    return new PagedUfsBlockReader(mUfsManager, mUfsInStreamCache, blockMeta,
        offset, readOptions, mPageSize);
  }

  @Override
  public void abortBlock(long sessionId, long blockId) {
    PagedTempBlockMeta blockMeta = mPageMetaStore.getTempBlock(blockId)
        .orElseThrow(() -> new BlockDoesNotExistRuntimeException(blockId));
    try {
      blockMeta.getDir().abort(BlockPageId.tempFileIdOf(blockId));
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
    for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
      synchronized (listener) {
        listener.onAbortBlock(blockId);
      }
    }
  }

  @Override
  public void requestSpace(long sessionId, long blockId, long additionalBytes) {
    // TODO(bowen): implement actual space allocation and replace placeholder values
    boolean blockEvicted = false;
    if (blockEvicted) {
      long evictedBlockId = 0;
      BlockStoreLocation evictedBlockLocation = new BlockStoreLocation(DEFAULT_TIER, 1);
      for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
        synchronized (listener) {
          listener.onRemoveBlockByWorker(evictedBlockId);
          listener.onRemoveBlock(evictedBlockId, evictedBlockLocation);
        }
      }
    }
  }

  @Override
  public CompletableFuture<List<BlockStatus>> load(List<Block> fileBlocks, UfsReadOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockMeta getBlockMeta(long sessionId, long blockId, long lockId)
      throws BlockDoesNotExistException, InvalidWorkerStateException {
    LOG.debug("getBlockMeta: sessionId={}, blockId={}, lockId={}", sessionId, blockId, lockId);
    mLockManager.validateLock(sessionId, blockId, lockId);
    try (LockResource r = new LockResource(mMetadataReadLock)) {
      Optional<PagedBlockMeta> pagedBlockMeta = mPageMetaStore.getBlock(blockId);
      return pagedBlockMeta.get();
    }
  }

  @Override
  public long lockBlock(long sessionId, long blockId) throws BlockDoesNotExistException {
    LOG.debug("lockBlock: sessionId={}, blockId={}", sessionId, blockId);
    long lockId = mLockManager.lockBlock(sessionId, blockId, BlockLockType.READ);
    boolean hasBlock;
    try (LockResource r = new LockResource(mMetadataReadLock)) {
      hasBlock = mPageMetaStore.hasBlock(blockId);
    }
    if (hasBlock) {
      return lockId;
    }

    mLockManager.unlockBlock(lockId);
    throw new BlockDoesNotExistException(ExceptionMessage.NO_BLOCK_ID_FOUND, blockId);
  }

  @Override
  public long lockBlockNoException(long sessionId, long blockId) {
    LOG.debug("lockBlockNoException: sessionId={}, blockId={}", sessionId, blockId);
    long lockId = mLockManager.lockBlock(sessionId, blockId, BlockLockType.READ);
    boolean hasBlock;
    try (LockResource r = new LockResource(mMetadataReadLock)) {
      hasBlock = mPageMetaStore.hasBlock(blockId);
    }
    if (hasBlock) {
      return lockId;
    }

    mLockManager.unlockBlockNoException(lockId);
    return BlockLockManager.INVALID_LOCK_ID;
  }

  @Override
  public void unlockBlock(long lockId) throws BlockDoesNotExistException {
    LOG.debug("unlockBlock: lockId={}", lockId);
    mLockManager.unlockBlock(lockId);
  }

  @Override
  public boolean unlockBlock(long sessionId, long blockId) {
    LOG.debug("unlockBlock: sessionId={}, blockId={}", sessionId, blockId);
    return mLockManager.unlockBlock(sessionId, blockId);
  }

  @Override
  public BlockWriter getBlockWriter(long sessionId, long blockId)
      throws BlockDoesNotExistException, BlockAlreadyExistsException, InvalidWorkerStateException,
      IOException {
    // TODO(JiamingMai): implement this method
    LOG.debug("getBlockWriter: sessionId={}, blockId={}", sessionId, blockId);
    return createBlockWriter(sessionId, blockId);
  }

  @Override
  public BlockWriter createBlockWriter(long sessionId, long blockId)
      throws IOException {
    // note: no need to take a write block lock here as the block will not be visible to other
    // clients until the block is committed
    try (LockResource lock = new LockResource(mPageMetaStore.getLock().writeLock())) {
      if (!mPageMetaStore.hasBlock(blockId) && !mPageMetaStore.hasTempBlock(blockId)) {
        PagedBlockStoreDir dir =
            (PagedBlockStoreDir) mPageMetaStore.allocate(BlockPageId.tempFileIdOf(blockId), 0);
        PagedTempBlockMeta blockMeta = new PagedTempBlockMeta(blockId, dir);
        mPageMetaStore.addTempBlock(blockMeta);
        return new PagedBlockWriter(mCacheManager, blockId, mPageSize);
      }
    }
    throw new AlreadyExistsRuntimeException(new BlockAlreadyExistsException(
        String.format("Cannot overwrite an existing block %d", blockId)));
  }

  /**
   * Return mCacheManager.mState.get() for CommitTest.
   * @return the mState, like READ_ONLY, READ_WRITE, NOT_IN_USE
   */
  public CacheManager.State getCacheManagerState() {
    return mCacheManager.state();
  }

  @Override
  public void moveBlock(long sessionId, long blockId, AllocateOptions moveOptions)
      throws IOException {
    // TODO(bowen): implement actual move and replace placeholder values
    int dirIndex = getDirIndexOfBlock(blockId);
    BlockStoreLocation srcLocation = new BlockStoreLocation(DEFAULT_TIER, dirIndex);
    BlockStoreLocation destLocation = moveOptions.getLocation();
    for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
      synchronized (listener) {
        listener.onMoveBlockByClient(blockId, srcLocation, destLocation);
      }
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeBlock(long sessionId, long blockId) throws IOException {
    LOG.debug("removeBlock: sessionId={}, blockId={}", sessionId, blockId);
    int dirIndex = getDirIndexOfBlock(blockId);
    removeBlockInternal(sessionId, blockId, REMOVE_BLOCK_TIMEOUT_MS);
    for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
      synchronized (listener) {
        listener.onRemoveBlockByClient(blockId);
        BlockStoreLocation removedFrom = new BlockStoreLocation(DEFAULT_TIER, dirIndex);
        listener.onRemoveBlock(blockId, removedFrom);
      }
    }
  }

  @Override
  public void accessBlock(long sessionId, long blockId) {
    // TODO(bowen): implement actual access and replace placeholder values
    boolean blockExists = true;
    if (blockExists) {
      int dirIndex = getDirIndexOfBlock(blockId);
      BlockStoreLocation dummyLoc = new BlockStoreLocation(DEFAULT_TIER, dirIndex);
      for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
        synchronized (listener) {
          listener.onAccessBlock(blockId);
          listener.onAccessBlock(blockId, dummyLoc);
        }
      }
    }
    //throw new UnsupportedOperationException();
  }

  @Override
  public BlockStoreMeta getBlockStoreMeta() {
    return mPageMetaStore.getStoreMeta();
  }

  @Override
  public BlockStoreMeta getBlockStoreMetaFull() {
    return mPageMetaStore.getStoreMetaFull();
  }

  @Override
  public Optional<TempBlockMeta> getTempBlockMeta(long blockId) {
    return Optional.empty();
  }

  @Override
  public boolean hasBlockMeta(long blockId) {
    return mPageMetaStore.getBlock(blockId).isPresent();
  }

  @Override
  public boolean hasTempBlockMeta(long blockId) {
    return mPageMetaStore.getTempBlock(blockId).isPresent();
  }

  @Override
  public Optional<BlockMeta> getVolatileBlockMeta(long blockId) {
    return Optional.empty();
  }

  @Override
  public void cleanupSession(long sessionId) {
    // TODO(bowen): session cleaner seems to be defunct, as Sessions are always empty
  }

  @Override
  public void registerBlockStoreEventListener(BlockStoreEventListener listener) {
    mBlockStoreEventListeners.add(listener);
    mPageMetaStore.registerBlockStoreEventListener(listener);
  }

  @Override
  public void updatePinnedInodes(Set<Long> inodes) {
    // TODO(bowen): this is unused now, make sure to use the pinned inodes when allocating space
    LOG.debug("updatePinnedInodes: inodes={}", inodes);
    synchronized (mPinnedInodes) {
      mPinnedInodes.clear();
      mPinnedInodes.addAll(Preconditions.checkNotNull(inodes));
    }
  }

  @Override
  public void removeInaccessibleStorage() {
    // TODO(bowen): implement actual removal and replace placeholder values
    for (BlockStoreEventListener listener : mBlockStoreEventListeners) {
      synchronized (listener) {
        List<Long> lostBlocks = ImmutableList.of();
        // TODO(bowen): lost directories can be obtained by iterating dirs in PageMetaStore
        // and check their health
        String lostStoragePath = "lostDir";
        BlockStoreLocation lostStoreLocation = new BlockStoreLocation(DEFAULT_TIER, 1);
        for (long lostBlock : lostBlocks) {
          listener.onBlockLost(lostBlock);
        }
        listener.onStorageLost(DEFAULT_TIER, lostStoragePath);
        listener.onStorageLost(lostStoreLocation);
      }
    }
  }

  @Override
  public void close() throws IOException {
  }

  private int getDirIndexOfBlock(long blockId) {
    return mPageMetaStore.getBlock(blockId)
        .orElseThrow(() -> new BlockDoesNotExistRuntimeException(blockId))
        .getDir()
        .getDirIndex();
  }

  private void removeBlockInternal(long sessionId, long blockId, long timeoutMs)
      throws IOException {
    Optional<BlockLock> optionalLock =
        mLockManager.tryAcquireBlockLock(sessionId, blockId, BlockLockType.WRITE,
        timeoutMs, TimeUnit.MILLISECONDS);
    if (!optionalLock.isPresent()) {
      throw new DeadlineExceededException(
        String.format("Can not acquire lock to remove block %d for session %d after %d ms",
        blockId, sessionId, timeoutMs));
    }
    try (BlockLock blockLock = optionalLock.get()) {
      Set<PageId> pageIds;
      try (LockResource metaLock = new LockResource(mPageMetaStore.getLock().writeLock())) {
        if (mPageMetaStore.hasTempBlock(blockId)) {
          throw new IllegalStateException(
            ExceptionMessage.REMOVE_UNCOMMITTED_BLOCK.getMessage(blockId));
        }
        pageIds = mPageMetaStore.getBlock(blockId)
          .orElseThrow(() -> new BlockDoesNotExistRuntimeException(blockId))
          .getDir().getBlockPages(blockId);

        for (PageId pageId : pageIds) {
          PageInfo pageInfo = mPageMetaStore.removePage(pageId);
          pageInfo.getLocalCacheDir().getPageStore().delete(pageId);
        }
      }
    } catch (PageNotFoundException e) {
      throw new NotFoundRuntimeException("Page not found: " + e.getMessage(), e);
    }
  }
}
