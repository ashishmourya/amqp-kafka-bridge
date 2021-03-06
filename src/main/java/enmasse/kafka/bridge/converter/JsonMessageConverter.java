/*
 * Copyright 2016 Red Hat Inc.
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

package enmasse.kafka.bridge.converter;

import enmasse.kafka.bridge.Bridge;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.Message;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * JSON implementation class for the message conversion
 * between Kafka record and AMQP message
 */
public class JsonMessageConverter implements MessageConverter<String, byte[]> {

	// AMQP message section to encode in JSON
	public static final String APPLICATION_PROPERTIES = "applicationProperties";
	public static final String PROPERTIES = "properties";
	public static final String MESSAGE_ANNOTATIONS = "messageAnnotations";
	public static final String BODY = "body";
	
	public static final String SECTION_TYPE = "type";
	public static final String SECTION = "section";
	public static final String SECTION_AMQP_VALUE_TYPE = "amqpValue";
	public static final String SECTION_DATA_TYPE = "data";
	
	// main AMQP properties
	public static final String MESSAGE_ID = "messageId";
	public static final String TO = "to";
	public static final String SUBJECT = "subject";
	public static final String REPLY_TO = "replyTo";
	public static final String CORRELATION_ID = "correlationId";
	
	@Override
	public ProducerRecord<String, byte[]> toKafkaRecord(String kafkaTopic, Message message) {
		
		Object partition = null, key = null;
		byte[] value = null;
		
		// root JSON
		JsonObject json = new JsonObject();
		
		// make JSON with AMQP properties
		JsonObject jsonProperties = new JsonObject();
		if (message.getMessageId() != null)
			jsonProperties.put(JsonMessageConverter.MESSAGE_ID, message.getMessageId());
		if (message.getAddress() != null)
			jsonProperties.put(JsonMessageConverter.TO, message.getAddress());
		if (message.getSubject() != null)
			jsonProperties.put(JsonMessageConverter.SUBJECT, message.getSubject());
		if (message.getReplyTo() != null)
			jsonProperties.put(JsonMessageConverter.REPLY_TO, message.getReplyTo());
		if (message.getCorrelationId() != null)
			jsonProperties.put(JsonMessageConverter.CORRELATION_ID, message.getCorrelationId());
		if (!jsonProperties.isEmpty())
			json.put(JsonMessageConverter.PROPERTIES, jsonProperties);
		
		// make JSON with AMQP application properties 
		ApplicationProperties applicationProperties = message.getApplicationProperties();
		
		if (applicationProperties != null) {
			
			JsonObject jsonApplicationProperties = new JsonObject();
			Map<String, Object> applicationPropertiesMap = (Map<String, Object>)applicationProperties.getValue();
			for (Entry<String, Object> entry : applicationPropertiesMap.entrySet()) {
				
				jsonApplicationProperties.put(entry.getKey(), entry.getValue());
			}
			json.put(JsonMessageConverter.APPLICATION_PROPERTIES, jsonApplicationProperties);
		}
		
		// get partition and key from AMQP message annotations
		// NOTE : they are not mandatory
		MessageAnnotations messageAnnotations = message.getMessageAnnotations();
		
		if (messageAnnotations != null) {
			
			partition = messageAnnotations.getValue().get(Symbol.getSymbol(Bridge.AMQP_PARTITION_ANNOTATION));
			key = messageAnnotations.getValue().get(Symbol.getSymbol(Bridge.AMQP_KEY_ANNOTATION));
			
			if (partition != null && !(partition instanceof Integer))
				throw new IllegalArgumentException("The partition annotation must be an Integer");
			
			if (key != null && !(key instanceof String))
				throw new IllegalArgumentException("The key annotation must be a String");
			
			// make JSON with AMQP message annotations
			JsonObject jsonMessageAnnotations = new JsonObject();
			Map<Symbol, Object> messageAnnotationsMap = (Map<Symbol, Object>)messageAnnotations.getValue();
			for (Entry<Symbol, Object> entry : messageAnnotationsMap.entrySet()) {
				
				jsonMessageAnnotations.put(entry.getKey().toString(), entry.getValue());
			}
			json.put(JsonMessageConverter.MESSAGE_ANNOTATIONS, jsonMessageAnnotations);
		}
		
		// get topic and body from AMQP message
		String topic = (message.getAddress() == null) ?
				kafkaTopic :
				message.getAddress().replace('/', '.');

		Section body = message.getBody();
		
		// check body null
		if (body != null) {
			
			JsonObject jsonBody = new JsonObject();
			
			// section is AMQP value
			if (body instanceof AmqpValue) {	
				
				jsonBody.put(JsonMessageConverter.SECTION_TYPE, JsonMessageConverter.SECTION_AMQP_VALUE_TYPE);
				
				Object amqpValue = ((AmqpValue) body).getValue();
				
				// encoded as String
				if (amqpValue instanceof String) {
					String content = (String)((AmqpValue) body).getValue();
					jsonBody.put(JsonMessageConverter.SECTION, content);
				// encoded as a List
				} else if (amqpValue instanceof List) {
					List<?> list = (List<?>)((AmqpValue) body).getValue();
					JsonArray jsonArray = new JsonArray(list);
					jsonBody.put(JsonMessageConverter.SECTION, jsonArray);
				// encoded as an array
				} else if (amqpValue instanceof Object[]) {
					Object[] array = (Object[])((AmqpValue)body).getValue();
					JsonArray jsonArray = new JsonArray(Arrays.asList(array));
					jsonBody.put(JsonMessageConverter.SECTION, jsonArray);
				// encoded as a Map
				} else if (amqpValue instanceof Map) {
					Map<?,?> map = (Map<?,?>)((AmqpValue)body).getValue();
					value = map.toString().getBytes();
					JsonObject jsonMap = new JsonObject((Map<String,Object>)map);
					jsonBody.put(JsonMessageConverter.SECTION, jsonMap);
				}
			
			// section is Data (binary)
			} else if (body instanceof Data) {
				Binary binary = (Binary)((Data)body).getValue();
				value = binary.getArray();
				
				jsonBody.put(JsonMessageConverter.SECTION_TYPE, JsonMessageConverter.SECTION_DATA_TYPE);
				
				// put the section bytes as Base64 encoded string
				jsonBody.put(JsonMessageConverter.SECTION, Base64.getEncoder().encode(value));
			}
			
			// put the body into the JSON root
			json.put(JsonMessageConverter.BODY, jsonBody);
		}
		
		// build the record for the KafkaProducer and then send it
		return new ProducerRecord<>(topic, (Integer)partition, (String)key, json.toString().getBytes());
	}

	@Override
	public Message toAmqpMessage(String amqpAddress, ConsumerRecord<String, byte[]> record) {
		
		Message message = Proton.message();
		message.setAddress(amqpAddress);
		
		// get the root JSON
		JsonObject json = new JsonObject(new String(record.value()));
		
		// get AMQP properties from the JSON
		JsonObject jsonProperties = json.getJsonObject(JsonMessageConverter.PROPERTIES);
		if (jsonProperties != null) {
			
			for (Entry<String, Object> entry : jsonProperties) {
				
				if (entry.getValue() != null) {
				
					if (entry.getKey().equals(JsonMessageConverter.MESSAGE_ID)) {
						message.setMessageId(entry.getValue());
					} else if (entry.getKey().equals(JsonMessageConverter.TO)) {
						message.setAddress(entry.getValue().toString());
					} else if (entry.getKey().equals(JsonMessageConverter.SUBJECT)) {
						message.setSubject(entry.getValue().toString());
					} else if (entry.getKey().equals(JsonMessageConverter.REPLY_TO)) {
						message.setReplyTo(entry.getValue().toString());
					} else if (entry.getKey().equals(JsonMessageConverter.CORRELATION_ID)) {
						message.setCorrelationId(entry.getValue());
					}
				}
			}
		}
		
		// get AMQP application properties from the JSON
		JsonObject jsonApplicationProperties = json.getJsonObject(JsonMessageConverter.APPLICATION_PROPERTIES);
		if (jsonApplicationProperties != null) {
			
			Map<Symbol, Object> applicationPropertiesMap = new HashMap<>();
			
			for (Entry<String, Object> entry : jsonApplicationProperties) {
				applicationPropertiesMap.put(Symbol.valueOf(entry.getKey()), entry.getValue());
			}
			
			ApplicationProperties applicationProperties = new ApplicationProperties(applicationPropertiesMap); 
			message.setApplicationProperties(applicationProperties);
		}
		
		// put message annotations about partition, offset and key (if not null)
		Map<Symbol, Object> messageAnnotationsMap = new HashMap<>();
		messageAnnotationsMap.put(Symbol.valueOf(Bridge.AMQP_PARTITION_ANNOTATION), record.partition());
		messageAnnotationsMap.put(Symbol.valueOf(Bridge.AMQP_OFFSET_ANNOTATION), record.offset());
		if (record.key() != null)
			messageAnnotationsMap.put(Symbol.valueOf(Bridge.AMQP_KEY_ANNOTATION), record.key());
		
		// get AMQP message annotations from the JSON
		JsonObject jsonMessageAnnotations = json.getJsonObject(JsonMessageConverter.MESSAGE_ANNOTATIONS);
		if (jsonMessageAnnotations != null) {
			
			for (Entry<String, Object> entry : jsonMessageAnnotations) {
				messageAnnotationsMap.put(Symbol.valueOf(entry.getKey()), entry.getValue());
			}
		}
		
		MessageAnnotations messageAnnotations = new MessageAnnotations(messageAnnotationsMap);
		message.setMessageAnnotations(messageAnnotations);
		
		// get the AMQP message body from the JSON
		JsonObject jsonBody = json.getJsonObject(JsonMessageConverter.BODY);
		
		if (jsonBody != null) {
			
			// type attribtute for following sectin : AMQP value or raw data/binary
			String type = jsonBody.getString(JsonMessageConverter.SECTION_TYPE);
			 
			if (type.equals(JsonMessageConverter.SECTION_AMQP_VALUE_TYPE)) {
				
				// section is an AMQP value
				Object jsonSection = jsonBody.getValue(JsonMessageConverter.SECTION);
				
				// encoded as String
				if (jsonSection instanceof String) {
					message.setBody(new AmqpValue(jsonSection));
				// encoded as an array/List
				} else if (jsonSection instanceof JsonArray) {
					JsonArray jsonArray = (JsonArray)jsonSection;
					message.setBody(new AmqpValue(jsonArray.getList()));
				// encoded as a Map
				} else if (jsonSection instanceof JsonObject) {
					JsonObject jsonObject = (JsonObject)jsonSection;
					message.setBody(new AmqpValue(jsonObject.getMap()));
				}
				
			} else if (type.equals(JsonMessageConverter.SECTION_DATA_TYPE)) {
				
				// section is a raw binary data
				
				// get the section from the JSON (it's base64 encoded)
				byte[] value = jsonBody.getBinary(JsonMessageConverter.SECTION);
				
				message.setBody(new Data(new Binary(Base64.getDecoder().decode(value))));
			}
		}
		
		return message;
	}

	
}
