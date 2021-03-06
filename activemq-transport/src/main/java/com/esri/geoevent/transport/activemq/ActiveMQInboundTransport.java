/*
  Copyright 1995-2013 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  email: contracts@esri.com
 */

package com.esri.geoevent.transport.activemq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;
import com.esri.ges.transport.TransportException;
import com.esri.ges.util.Validator;

public class ActiveMQInboundTransport extends InboundTransportBase implements Runnable
{
	private static final BundleLogger	LOGGER	= BundleLoggerFactory.getLogger(ActiveMQInboundTransport.class);

	private String										errorMessage;
	private Connection								connection;
	private Session										session;
	private MessageConsumer						messageConsumer;
	private Thread										starterThread;
	private String										channelId;

	public ActiveMQInboundTransport(TransportDefinition definition) throws ComponentException
	{
		super(definition);
		// create a channel id per instance
		channelId = UUID.randomUUID().toString();
	}

	@Override
	public void run()
	{
		setRunningState(RunningState.STARTED);
		while (isRunning())
		{
			try
			{
				Message message = messageConsumer.receive(100);
				if (message != null)
				{
					if (message instanceof TextMessage)
					{
						TextMessage textMessage = (TextMessage) message;
						byte[] bytes = {};
						try
						{
							bytes = textMessage.getText().getBytes(StandardCharsets.UTF_8);
						}
						catch (Throwable error)
						{
							LOGGER.error("MESSAGE_DECODING_ERROR", error.getMessage());
							LOGGER.info(error.getMessage(), error);
						}
						receive(bytes);
					}
					else if (message instanceof BytesMessage)
					{
						BytesMessage bytesMessage = (BytesMessage) message;
						byte[] bytes = new byte[(int) bytesMessage.getBodyLength()];
						bytesMessage.readBytes(bytes);
						receive(bytes);
					}
					else if (message instanceof ObjectMessage)
					{
						ObjectMessage objectMessage = (ObjectMessage) message;
						Serializable object = objectMessage.getObject();
						ByteArrayOutputStream baos = null;
						ObjectOutput output = null;
						try
						{
							baos = new ByteArrayOutputStream();
							output = new ObjectOutputStream(baos);
							output.writeObject(object);
							receive(baos.toByteArray());
						}
						catch (IOException error)
						{
							LOGGER.error(error.getMessage(), error);
						}
						finally
						{
							if (output != null)
							{
								try
								{
									output.close();
								}
								catch (IOException ex)
								{
									;
								}
							}
							if (baos != null)
							{
								try
								{
									baos.close();
								}
								catch (IOException ex)
								{
									;
								}
							}
						}
					}
				}
			}
			catch (JMSException error)
			{
				LOGGER.error(error.getMessage(), error);
			}
		}
	}

	private void receive(byte[] bytes)
	{
		if (bytes != null && bytes.length > 0)
		{
			ByteBuffer bb = ByteBuffer.allocate(bytes.length);
			bb.put(bytes);
			bb.flip();
			byteListener.receive(bb, channelId);
			bb.clear();
		}
	}

	@SuppressWarnings("incomplete-switch")
	@Override
	public synchronized void start()
	{
		switch (getRunningState())
		{
			case STARTING:
			case STARTED:
				return;
		}
		if (starterThread == null || !starterThread.isAlive())
		{
			starterThread = new Thread(new ActiveMQByteInboundTransportStarter(this, 60000));
			starterThread.start();
		}
	}

	@Override
	public synchronized void stop()
	{
		if (!RunningState.STOPPED.equals(getRunningState()))
		{
			starterThread = null;
			setRunningState(RunningState.STOPPING);
			cleanup();
			setRunningState(RunningState.STOPPED);
		}
	}

	public String getErrorMessage()
	{
		return errorMessage;
	}

	private synchronized void setup() throws TransportException
	{
		try
		{
			if (getProperty("userName") != null && getProperty("password") != null)
			{
				ConnectionFactory factory = new ActiveMQConnectionFactory(getProperty("userName").getValueAsString(), getProperty("password").getDecryptedValue(), getProperty("providerUrl").getValueAsString());
				connection = factory.createConnection(getProperty("userName").getValueAsString(), getProperty("password").getDecryptedValue());
			}
			else
			{
				ConnectionFactory factory = new ActiveMQConnectionFactory(getProperty("providerUrl").getValueAsString());
				connection = factory.createConnection();
			}
			if (connection == null)
				throw new TransportException(LOGGER.translate("JMS_CONNECTION_FAILURE", getProperty("providerUrl").getValueAsString()));
			connection.setExceptionListener(new ExceptionListener()
				{
					@Override
					public void onException(JMSException exception)
					{
						setRunningState(RunningState.STOPPING);
						cleanup();
						setRunningState(RunningState.ERROR);
						errorMessage = exception.getMessage();
						LOGGER.error(errorMessage, exception);
					}
				});
			connection.start();
			session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			ActiveMQDestinationType type = Validator.valueOfIgnoreCase(ActiveMQDestinationType.class, getProperty("destinationType").getValueAsString(), ActiveMQDestinationType.Queue);
			messageConsumer = session.createConsumer(type.equals(ActiveMQDestinationType.Topic) ? session.createTopic(getProperty("destinationName").getValueAsString()) : session.createQueue(getProperty("destinationName").getValueAsString()));
		}
		catch (JMSException e)
		{
			cleanup();
			throw new TransportException(e.getMessage());
		}
		catch (Exception e)
		{
			cleanup();
			throw new TransportException(e.getMessage());
		}
	}

	private synchronized void cleanup()
	{
		try
		{
			if (messageConsumer != null)
			{
				messageConsumer.close();
				messageConsumer = null;
			}
		}
		catch (Throwable ignore)
		{
			;
		}
		try
		{
			if (session != null)
			{
				session.close();
				session = null;
			}
		}
		catch (Throwable ignore)
		{
			;
		}
		try
		{
			if (connection != null)
			{
				connection.close();
				connection = null;
			}
		}
		catch (Throwable ignore)
		{
			;
		}
	}

	private class ActiveMQByteInboundTransportStarter implements Runnable
	{
		private ActiveMQInboundTransport	transport;
		private long											timeout;

		public ActiveMQByteInboundTransportStarter(ActiveMQInboundTransport transport, long timeout)
		{
			this.transport = transport;
			this.timeout = timeout;
		}

		@Override
		public void run()
		{
			while (starterThread != null)
			{
				switch (getRunningState())
				{
					case STOPPING:
						break;
					case STOPPED:
					case ERROR:
					{
						try
						{
							setRunningState(RunningState.STARTING);
							setup();
							new Thread(transport).start();
						}
						catch (TransportException e)
						{
							errorMessage = e.getMessage();
							setRunningState(RunningState.ERROR);
							LOGGER.error(errorMessage, e);
						}
					}
						break;
					default:
						errorMessage = "";
						break;
				}
				sleep();
			}
		}

		private void sleep()
		{
			try
			{
				Thread.sleep(timeout);
			}
			catch (InterruptedException e)
			{
				;
			}
		}
	}
}
