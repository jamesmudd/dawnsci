/*-
 *******************************************************************************
 * Copyright (c) 2011, 2015 Diamond Light Source Ltd.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Matthew Gerring - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.dawnsci.data.server.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import org.dawnsci.plotting.services.ImageService;
import org.eclipse.dawnsci.analysis.api.dataset.IDataset;
import org.eclipse.dawnsci.data.client.DataClient;
import org.eclipse.dawnsci.data.server.DataServer;
import org.eclipse.dawnsci.data.server.Format;
import org.eclipse.dawnsci.data.server.ServiceHolder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import uk.ac.diamond.scisoft.analysis.dataset.function.Downsample;
import uk.ac.diamond.scisoft.analysis.osgi.LoaderServiceImpl;

/**
 * Runs as standard junit test.
 * Start the Data Server before running this test!
 * 
 * Test tests require that the DataServer is going and that the
 * data is at the pre-supposed locations.
 * 
 * TODO make this into a replicable unit test.
 * 
 * @author fcp94556
 *
 */ 
public class ClientTest {
	
	private static DataServer server;
	private static String     testDir;
	private static int        port;

	/**
	 * Programmatically start the DataServer OSGi application which runs
	 * under Jetty and starts jetty itself.
	 * @throws Exception 
	 */
	@BeforeClass
	public static void startDataServer() throws Exception {
		
		// Sorry but the concrete classes for these services are not part of an eclipse project.
		// To get these concrete services go to dawnsci.org and follow the instructions for
		// setting up dawnsci to run in your application.
		ServiceHolder.setDownService(new Downsample());
		ServiceHolder.setImageService(new ImageService());
		ServiceHolder.setLoaderService(new LoaderServiceImpl());
	
        // Start the DataServer
		port   = TestUtils.getFreePort(8080);
		server = new DataServer();
		server.setPort(port);
		server.start(false);
		
		System.out.println("Started DataServer on port "+port);
		
		File pluginDir = new File((new File("")).getAbsolutePath()); // Assuming test run in test plugin
		testDir = (new File(pluginDir, "testfiles")).getAbsolutePath();
	}
	
	@AfterClass
	public static void stop() {
		server.stop();
	}

	
	@Test
	public void testFullData() throws Exception {
		
		final DataClient<IDataset> client = new DataClient<IDataset>("http://localhost:"+port+"/slice/");
		client.setPath(testDir+"/export.h5");
		client.setDataset("/entry/edf/data");
		client.setSlice("[0,:2048,:2048]");

		final IDataset data = client.get();
		if (!Arrays.equals(data.getShape(), new int[]{2048, 2048})) {
			throw new Exception("Unexpected shape "+Arrays.toString(data.getShape()));
		}
	}

	@Test
	public void testDownsampledData() throws Exception {
		
		final DataClient<IDataset> client = new DataClient<IDataset>("http://localhost:"+port+"/slice/");
		client.setPath(testDir+"/export.h5");
		client.setDataset("/entry/edf/data");
		client.setSlice("[0,:2048,:2048]");
		client.setBin("MEAN:4x4");
		
		final IDataset data = client.get();
		if (!Arrays.equals(data.getShape(), new int[]{512, 512})) {
			throw new Exception("Unexpected shape "+Arrays.toString(data.getShape()));
		}

	}
	
	@Test
	public void testDownsampledJPG() throws Exception {
		
		final DataClient<BufferedImage> client = new DataClient<BufferedImage>("http://localhost:"+port+"/slice/");
		client.setPath(testDir+"/export.h5");
		client.setDataset("/entry/edf/data");
		client.setSlice("[0,:2048,:2048]");
		client.setBin("MEAN:4x4");
		client.setFormat(Format.JPG);
		client.setHisto("MEAN");
		
		final BufferedImage image = client.get();
		if (image.getHeight()!=512) throw new Exception("Unexpected image height '"+image.getHeight()+"'");
		if (image.getWidth()!=512)  throw new Exception("Unexpected image height '"+image.getWidth()+"'");
	}

	
	@Test
	public void testDownsampledMJPG() throws Exception {
		
		final DataClient<BufferedImage> client = new DataClient<BufferedImage>("http://localhost:"+port+"/slice/");
		client.setPath(testDir+"/export.h5");
		client.setDataset("/entry/edf/data");
		client.setSlice("[0,:2048,:2048]");
		client.setBin("MEAN:4x4");
		client.setFormat(Format.MJPG);
		client.setHisto("MEAN");
		client.setSleep(100); // Default anyway is 100ms
		
		
		int i = 0;
		while(!client.isFinished()) {
			
			final BufferedImage image = client.take();
			if (image ==null) break; // Last image in stream is null.
			if (image.getHeight()!=512) throw new Exception("Unexpected image height '"+image.getHeight()+"'");
			if (image.getWidth()!=512)  throw new Exception("Unexpected image height '"+image.getWidth()+"'");
			++i;
			System.out.println("Image "+i+" found");
		}
	
		if (i != 4) throw new Exception("4 images were not found! "+i+" were!");
	}
	
	@Test
	public void testFastMJPG() throws Exception {
		
		final DataClient<BufferedImage> client = new DataClient<BufferedImage>("http://localhost:"+port+"/slice/");
		client.setPath("RANDOM:512x512");
		client.setFormat(Format.MJPG);
		client.setHisto("MEAN");
		client.setSleep(10); // 100Hz - she's a fast one!
		
		int i = 0;
		while(!client.isFinished()) {
			
			final BufferedImage image = client.take();
			if (image ==null) break; // Last image in stream is null.
			if (image.getHeight()!=512) throw new Exception("Unexpected image height '"+image.getHeight()+"'");
			if (image.getWidth()!=512)  throw new Exception("Unexpected image height '"+image.getWidth()+"'");
			++i;
			if (i>1000) {
				client.setFinished(true);
				break; // That's enough of that
			}
			
			Thread.sleep(80);// Evil sleep means that take() is not as fast as send and there will be drops.
		}
	
		// We say
		System.out.println("Received images = "+i);
		System.out.println("Dropped images = "+client.getDroppedImageCount());
	}

	@Test
	public void testFastMDATA() throws Exception {
		
		final DataClient<IDataset> client = new DataClient<IDataset>("http://localhost:"+port+"/slice/");
		client.setPath("RANDOM:512x512");
		client.setFormat(Format.MDATA);
		client.setHisto("MEAN");
		client.setSleep(10); // 100Hz - she's a fast one!
		
		int i = 0;
		while(!client.isFinished()) {
			
			final IDataset image = client.take();
			if (image ==null) break; // Last image in stream is null.
			if (image.getShape()[0]!=512) throw new Exception("Unexpected image height '"+image.getShape()[0]+"'");
			if (image.getShape()[1]!=512)  throw new Exception("Unexpected image height '"+image.getShape()[1]+"'");
			++i;
			if (i>1000) {
				client.setFinished(true);
				break; // That's enough of that
			}
			
			Thread.sleep(80);// Evil sleep means that take() is not as fast as send and there will be drops.
		}
	
		// We say
		System.out.println("Received images = "+i);
		System.out.println("Dropped images = "+client.getDroppedImageCount());
	}
}
