/**
 * This file is part of General Entity Annotator Benchmark.
 *
 * General Entity Annotator Benchmark is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * General Entity Annotator Benchmark is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with General Entity Annotator Benchmark.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.aksw.dice.eaglet.uri.impl;

import java.io.IOException;
import java.net.UnknownHostException;

import org.aksw.dice.eaglet.entitytypemodify.NamedEntityCorrections.Correction;
import org.aksw.dice.eaglet.entitytypemodify.NamedEntityCorrections.ErrorType;
import org.aksw.dice.eaglet.uri.UriChecker;
import org.aksw.gerbil.http.AbstractHttpRequestEmitter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.impl.PropertyImpl;

public class HttpBasedUriChecker extends AbstractHttpRequestEmitter implements UriChecker {

	private static final Logger LOGGER = LoggerFactory.getLogger(HttpBasedUriChecker.class);

	private static final String REQUEST_ACCEPT_HEADER_VALUE = RDFLanguages.RDFXML.getContentType().getContentType();
	private static final Property DISAMBIGUATION_PROPERTY = new PropertyImpl(
			"http://dbpedia.org/ontology/wikiPageDisambiguates");

	public HttpBasedUriChecker() {
	}

	public HttpBasedUriChecker(CloseableHttpClient client) {
		super(client);
	}

	public HttpBasedUriChecker(String name) {
		super(name);
	}

	public HttpBasedUriChecker(String name, CloseableHttpClient client) {
		super(name, client);
	}

	@Override
	public ErrorType checkUri(String uri) {
		if ((uri == null) || (uri.isEmpty())) {
			LOGGER.info("INVALID_URI \"{}\"", uri);
			return ErrorType.INVALIDURIERR;
		}
		Model model = null;
		try {
			model = requestModel(uri);
		} catch (org.apache.jena.atlas.web.HttpException e) {
			LOGGER.debug("HTTP Exception while requesting uri \"{}\". Returning INVALID_URI. Exception: {}", uri,
					e.getMessage());
			LOGGER.info("INVALID_URI \"{}\" because model caused exception", uri);
			return ErrorType.INVALIDURIERR;

		} catch (org.apache.jena.riot.RiotException e) {
			LOGGER.debug(
					"Riot Exception while parsing requested model of uri \"{}\". Returning INVALID_URI. Exception: {}",
					uri, e.getMessage());
			LOGGER.info("INVALID_URI \"{}\" because model caused exception", uri);
			return ErrorType.INVALIDURIERR;

		} catch (Exception e) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Exception while requesting uri \"" + uri + "\". Returning INVALID_URI.", e);
			}
			LOGGER.info("INVALID_URI \"{}\" because model caused exception", uri);
			return ErrorType.INVALIDURIERR;

		}
		if ((model == null) || (model.isEmpty())) {
			LOGGER.info("INVALID_URI \"{}\" because model==null", uri);
			return ErrorType.INVALIDURIERR;

		}
		Resource entity = model.getResource(uri);
		if (model.contains(entity, DISAMBIGUATION_PROPERTY)) {
			LOGGER.info("DISAMBIG_URI \"{}\"", uri);
			return ErrorType.DISAMBIGURIERR;
		} else {
			return ErrorType.NOERROR;
		}
	}

	protected Model requestModel(String uri) {
		HttpGet request = null;
		try {
			request = createGetRequest(uri);
		} catch (IllegalArgumentException e) {
			LOGGER.error("Exception while sending request for \"" + uri + "\". Returning null.", e);
			return null;
		}
		request.addHeader(HttpHeaders.ACCEPT, REQUEST_ACCEPT_HEADER_VALUE);
		request.addHeader(HttpHeaders.ACCEPT_CHARSET, "UTF-8");

		HttpEntity entity = null;
		CloseableHttpResponse response = null;
		Model model = null;
		try {

			try {
				response = client.execute(request);
			} catch (java.net.SocketException e) {
				if (e.getMessage().contains(CONNECTION_ABORT_INDICATING_EXCPETION_MSG)) {
					LOGGER.error("It seems like requesting the model of \"" + uri
							+ "\" needed too much time and was interrupted. Returning null.");
					return null;
				} else {
					LOGGER.error("Exception while sending request to \"" + uri + "\". Returning null.", e);
					return null;
				}
			} catch (UnknownHostException e) {
				LOGGER.info("Couldn't find host of \"" + uri + "\". Returning null.");
				return null;
			} catch (Exception e) {
				LOGGER.error("Exception while sending request to \"" + uri + "\". Returning null.", e);
				return null;
			}
			StatusLine status = response.getStatusLine();
			if ((status.getStatusCode() < 200) || (status.getStatusCode() >= 300)) {
				LOGGER.warn("Response of \"{}\" has the wrong status ({}). Returning null.", uri, status.toString());
				return null;
			}
			// receive NIF document
			entity = response.getEntity();
			Header contentTypeHeader = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
			if (contentTypeHeader == null) {
				LOGGER.error("The response did not contain a content type header. Returning null.");
				return null;
			}
			ContentType contentType = ContentType.create(contentTypeHeader.getValue());
			Lang language = RDFLanguages.contentTypeToLang(contentType);
			if (language == null) {
				LOGGER.error("Couldn't find an RDF language for the content type header value \"{}\". Returning null.",
						contentTypeHeader.getValue());
				return null;
			}
			// read response and parse NIF
			try {
				model = ModelFactory.createDefaultModel();
				RDFDataMgr.read(model, entity.getContent(), language);
			} catch (Exception e) {
				LOGGER.error("Couldn't parse the response for the URI \"" + uri + "\". Returning null", e);
			}
		} finally {
			if (entity != null) {
				try {
					EntityUtils.consume(entity);
				} catch (IOException e1) {
				}
			}
			if (response != null) {
				try {
					response.close();
				} catch (IOException e) {
				}
			}
			closeRequest(request);
		}
		return model;
	}

}
