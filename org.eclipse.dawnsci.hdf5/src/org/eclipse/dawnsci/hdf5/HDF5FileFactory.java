/*-
 * Copyright 2015 Diamond Light Source Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.eclipse.dawnsci.hdf5;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.dawnsci.analysis.api.io.ScanFileHolderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ncsa.hdf.hdf5lib.H5;
import ncsa.hdf.hdf5lib.HDF5Constants;
import ncsa.hdf.hdf5lib.exceptions.HDF5LibraryException;

/**
 * This contains method to access low level HDF5 file IDs and allows them to
 * be held open for a set period (default of 5s) before being closed.
 */
public class HDF5FileFactory {
	private static final Logger logger = LoggerFactory.getLogger(HDF5FileFactory.class);

	static class FileAccess {
		long id;   // HDF5 low level ID
		long time; // time of release
		int count; // number of accessors
		boolean writeable; // if true then can write
	}

	private static long heldPeriod = 5000; // 5 seconds

	private static boolean backwardCompatible = false;

	private final static HDF5FileFactory INSTANCE;

	static {
		INSTANCE = new HDF5FileFactory();
	}

	private ConcurrentMap<String, FileAccess> map;

	// Need singleton to add finalizer
	private HDF5FileFactory() {
		map = new ConcurrentHashMap<>();
	}

	@Override
	protected void finalize() throws Throwable {
		synchronized (map) {
			for (String f : map.keySet()) {
				FileAccess a = map.get(f);
				try {
					H5.H5Fclose(a.id);
				} catch (HDF5LibraryException e) {
					logger.error("Could not close file: {}", f);
				}
			}
		}
		super.finalize();
	}

	/**
	 * Set period of time a file ID is held open for. The period specified must be greater
	 * than or equal to 100 ms.
	 * @param heldPeriod in milliseconds
	 */
	public static void setHeldPeriod(long heldPeriod) {
		if (heldPeriod < 100) {
			throw new IllegalArgumentException();
		}
		HDF5FileFactory.heldPeriod = heldPeriod;
	}

	/**
	 * Get period of time a file ID is held open for.
	 * @return period held in milliseconds
	 */
	public static long getHeldPeriod() {
		return heldPeriod;
	}

	/**
	 * Set write to be backwardly compatible
	 * @param backwardCompatible
	 */
	public static void setWriteBackwardCompatible(boolean backwardCompatible) {
		HDF5FileFactory.backwardCompatible = backwardCompatible;
	}

	static {
		new Thread(new Runnable() {
			@Override
			public void run() {
				long waitPeriod = heldPeriod;
				while (true) {
					if (waitPeriod >= 0) {
						try {
							Thread.sleep(waitPeriod);
						} catch (InterruptedException e) {
						}
					}

					long now = System.currentTimeMillis();
					long next = now + heldPeriod;
					synchronized (INSTANCE) {
						Iterator<String> iter = INSTANCE.map.keySet().iterator();
						while (iter.hasNext()) {
							String f = iter.next();
							FileAccess a = INSTANCE.map.get(f);
							if (a.count <= 0) {
								if (a.time <= now) {
									try {
										if (H5.H5Fget_obj_count(a.id, HDF5Constants.H5F_OBJ_ATTR | HDF5Constants.H5F_OBJ_DATATYPE | HDF5Constants.H5F_OBJ_DATASET | HDF5Constants.H5F_OBJ_GROUP) > 0) {
											logger.error("There are things left open in " + f);
										}
										H5.H5Fclose(a.id);
										INSTANCE.map.remove(f);
// FIXME for CustomTomoConverter, etc 
//										HierarchicalDataFactory.releaseLowLevelReadingAccess(f);
									} catch (HDF5LibraryException e) {
										logger.error("Could not close file {}", f, e);
									}
								} else if (a.time < next) {
									next = a.time; // reduce waiting to next earliest between now and next
								}
							}
						}
					}
					waitPeriod = next - System.currentTimeMillis();
				}
			}
		}, "File ID releaser").start();

		// make sure all files are closed on shutdown
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					INSTANCE.finalize();
				} catch (Throwable e) {
				}
			}
		}, "File ID closer"));
	}

	/**
	 * Acquire file ID
	 * @param fileName
	 * @param writeable
	 * @param asNew
	 * @return file ID
	 * @throws ScanFileHolderException
	 */
	private static long acquireFile(String fileName, boolean writeable, boolean asNew) throws ScanFileHolderException {
		final String cPath;
		try {
			cPath = HierarchicalDataFactory.canonicalisePath(fileName);
		} catch (IOException e) {
			logger.error("Problem canonicalising path", e);
			throw new ScanFileHolderException("Problem canonicalising path", e);
		}

		FileAccess access = null;
		long fid = -1;
		long fapl = -1;

		synchronized (INSTANCE) {
			try {
				if (INSTANCE.map.containsKey(cPath)) {
					if (asNew) {
						logger.error("File already open and will need to closed: {}", cPath);
						throw new ScanFileHolderException("File already open and will need to closed");
					}
					access = INSTANCE.map.get(cPath);
					if (writeable && !access.writeable) {
						logger.error("Cannot get file {} in writeable state as it has been opened read-only", cPath);
						throw new ScanFileHolderException("Cannot get file in writeable state as it has been opened read-only");
					}
					access.count++;
					fid = access.id;
				} else {
// FIXME for CustomTomoConverter, etc 
//					HierarchicalDataFactory.acquireLowLevelReadingAccess(cPath);
					try {
						access = new FileAccess();
						access.count = 1;
						fapl = H5.H5Pcreate(HDF5Constants.H5P_FILE_ACCESS);
						if (!writeable || !backwardCompatible) {
							H5.H5Pset_libver_bounds(fapl, HDF5Constants.H5F_LIBVER_LATEST, HDF5Constants.H5F_LIBVER_LATEST);
						}
						if (asNew) {
							access.writeable = true;
							fid = H5.H5Fcreate(cPath, HDF5Constants.H5F_ACC_TRUNC, HDF5Constants.H5P_DEFAULT, fapl);
						} else {
							access.writeable = writeable;
							if (new File(cPath).exists()) {
								fid = H5.H5Fopen(cPath, writeable ? HDF5Constants.H5F_ACC_RDWR : HDF5Constants.H5F_ACC_RDONLY | HDF5Constants.H5F_ACC_SWMR_READ, fapl);
							} else if (!writeable) {
								logger.error("File {} does not exist!", cPath);
								throw new FileNotFoundException("File does not exist!");
							} else {
								fid = H5.H5Fcreate(cPath, HDF5Constants.H5F_ACC_EXCL, HDF5Constants.H5P_DEFAULT, fapl);
							}
						}
					} finally {
						if (fapl != -1) {
							H5.H5Pclose(fapl);
						}
					}
					access.id = fid;
					INSTANCE.map.put(cPath, access);
				}
			} catch (Throwable le) {
// FIXME for CustomTomoConverter, etc 
//				if (!IDS.containsKey(cPath)) {
//					HierarchicalDataFactory.releaseLowLevelReadingAccess(cPath);
//				}
				logger.error("Could not acquire access to file: {}", cPath, le);
				throw new ScanFileHolderException("Could not acquire access to file: " + cPath, le);
			}
		}

		return fid;
	}

	/**
	 * Acquire file ID
	 * @param fileName
	 * @param writeable
	 * @return file ID
	 * @throws ScanFileHolderException
	 */
	public static long acquireFile(String fileName, boolean writeable) throws ScanFileHolderException {
		return acquireFile(fileName, writeable, false);
	}

	/**
	 * Acquire file ID
	 * @param fileName
	 * @return file ID
	 * @throws ScanFileHolderException
	 */
	public static long acquireFileAsNew(String fileName) throws ScanFileHolderException {
		return acquireFile(fileName, true, true);
	}

	/**
	 * Delete file
	 * @param fileName
	 * @throws ScanFileHolderException
	 */
	public static void deleteFile(String fileName) throws ScanFileHolderException {
		final String cPath;
		try {
			cPath = HierarchicalDataFactory.canonicalisePath(fileName);
		} catch (IOException e) {
			logger.error("Problem canonicalising path", e);
			throw new ScanFileHolderException("Problem canonicalising path", e);
		}

		synchronized (INSTANCE) {
			if (INSTANCE.map.containsKey(cPath)) {
				try {
					FileAccess access = INSTANCE.map.get(cPath);
					if (access.count <= 0) {
						try {
							H5.H5Fclose(access.id);
							INSTANCE.map.remove(cPath);
// FIXME for CustomTomoConverter, etc 
//							HierarchicalDataFactory.releaseLowLevelReadingAccess(cPath); 
						} catch (HDF5LibraryException e) {
							logger.error("Could not close file", e);
							throw e;
						}
					} else {
						logger.error("File is currently being used: {}", cPath);
						throw new ScanFileHolderException("File is currently being used: " + cPath);
					}
				} catch (Throwable le) {
					logger.error("Problem releasing access to file: {}", cPath, le);
					throw new ScanFileHolderException("Problem releasing access to file: " + cPath, le);
				}
			}
		}

		File f = new File(cPath);
		if (f.exists()) {
			f.delete();
		}
	}

	/**
	 * Release file ID
	 * @param fileName
	 * @throws ScanFileHolderException
	 */
	public static void releaseFile(String fileName) throws ScanFileHolderException {
		releaseFile(fileName, false);
	}

	/**
	 * Release ID associated with file
	 * @param fileName
	 * @param close if true then close it too
	 * @throws ScanFileHolderException
	 */
	public static void releaseFile(String fileName, boolean close) throws ScanFileHolderException {
		final String cPath;
		try {
			cPath = HierarchicalDataFactory.canonicalisePath(fileName);
		} catch (IOException e) {
			logger.error("Problem canonicalising path", e);
			throw new ScanFileHolderException("Problem canonicalising path", e);
		}

		synchronized (INSTANCE) {
			if (!INSTANCE.map.containsKey(cPath)) {
				logger.debug("File not known - has it already been released?");
				return;
			}
		
			try {
				FileAccess access = INSTANCE.map.get(cPath);
				access.count--;
				if (access.count <= 0) {
					if (close) {
						try {
							H5.H5Fclose(access.id);
							INSTANCE.map.remove(cPath);
// FIXME for CustomTomoConverter, etc 
//							HierarchicalDataFactory.releaseLowLevelReadingAccess(cPath); 
						} catch (HDF5LibraryException e) {
							logger.error("Could not close file", e);
							throw e;
						}
					} else {
						access.time = System.currentTimeMillis() + heldPeriod; // update release time
					}
				}
			} catch (Throwable le) {
				logger.error("Problem releasing access to file: {}", cPath, le);
				throw new ScanFileHolderException("Problem releasing access to file: " + cPath, le);
			}
		}
	}
}
