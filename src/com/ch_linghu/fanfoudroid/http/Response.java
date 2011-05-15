package com.ch_linghu.fanfoudroid.http;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import android.util.Log;

import com.ch_linghu.fanfoudroid.weibo.Configuration;

public class Response {
//    private final static String TAG = "HttpClient";
    private final static String TAG = "Response";
    
    private final static boolean DEBUG = Configuration.getDebug();

    private static ThreadLocal<DocumentBuilder> builders =
            new ThreadLocal<DocumentBuilder>() {
                @Override
                protected DocumentBuilder initialValue() {
                    try {
                        return DocumentBuilderFactory.newInstance()
                                        .newDocumentBuilder();
                    } catch (ParserConfigurationException ex) {
                        throw new ExceptionInInitializerError(ex);
                    }
                }
            };

    private int statusCode;
    private Document responseAsDocument = null;
    private String responseAsString = null;
    private InputStream is;
    private HttpURLConnection con;
    private boolean streamConsumed = false;
    
    private HttpResponse response;
    private StatusLine statusLine;
    private Header[] responseHeader;
    
    private String contentEncoding; 

    public Response()  {
    	
    }
    public Response(HttpResponse response) throws IOException {
    	this.response = response;
    	this.statusLine = response.getStatusLine();
        this.statusCode = statusLine.getStatusCode();
        this.responseHeader = response.getAllHeaders();
        
        HttpEntity entity = response.getEntity();
        this.is = entity.getContent();
        Header contentEncoding = entity.getContentEncoding();
        if (null != is && contentEncoding != null
                && "gzip".equals(contentEncoding.getValue())) {
            // the response is gzipped
            is = new GZIPInputStream(is);
        }
        
        /*
        if (DEBUG) {
            HttpEntity debugEntity = new BufferedHttpEntity(entity);
        }
        */
    }

    // for test purpose
    /*package*/ Response(String content) {
        this.responseAsString = content;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseHeader(String name) {
    	if (response != null) {
    		Header[] header = response.getHeaders(name);
    		if (header.length > 0) {
    			return header[0].getValue();
    		}
    	}
   		return null;
//    	return con.getHeaderField(name);
    }

    /**
     * Returns the response stream.<br>
     * This method cannot be called after calling asString() or asDcoument()<br>
     * It is suggested to call disconnect() after consuming the stream.
     *
     * Disconnects the internal HttpURLConnection silently.
     * @return response body stream
     * @throws HttpException
     * @see #disconnect()
     */
    public InputStream asStream() {
        if(streamConsumed){
            throw new IllegalStateException("Stream has already been consumed.");
        }
        return is;
    }

    /**
     * Returns the response body as string.<br>
     * Disconnects the internal HttpURLConnection silently.
     * @return response body
     * @throws HttpException
     */
    public String asString() throws ResponseException {
        if(null == responseAsString){
            BufferedReader br;
            try {
                InputStream stream = asStream();
                if (null == stream) {
                    return null;
                }
                br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                StringBuffer buf = new StringBuffer();
                char[] buffer = new char[1024];
                while ( (br.read(buffer)) != -1 ) {
                    buf.append(buffer);
                }
                this.responseAsString = buf.toString();
                this.responseAsString = unescape(responseAsString);
                stream.close();
                streamConsumed = true;
            } catch (NullPointerException npe) {
                // don't remember in which case npe can be thrown
                throw new ResponseException(npe.getMessage(), npe);
            } catch (IOException ioe) {
                throw new ResponseException(ioe.getMessage(), ioe);
            }
        }
        return responseAsString;
    }

    /**
     * Returns the response body as org.w3c.dom.Document.<br>
     * Disconnects the internal HttpURLConnection silently.
     * @return response body as org.w3c.dom.Document
     * @throws HttpException
     */
    public Document asDocument() throws ResponseException {
        if (null == responseAsDocument) {
            try {
                // it should be faster to read the inputstream directly.
                // but makes it difficult to troubleshoot
                this.responseAsDocument = builders.get().parse(new ByteArrayInputStream(asString().getBytes("UTF-8")));
            } catch (SAXException saxe) {
                throw new ResponseException("The response body was not well-formed:\n" + responseAsString, saxe);
            } catch (IOException ioe) {
                throw new ResponseException("There's something with the connection.", ioe);
            }
        }
        return responseAsDocument;
    }

    /**
     * Returns the response body as sinat4j.org.json.JSONObject.<br>
     * Disconnects the internal HttpURLConnection silently.
     * @return response body as sinat4j.org.json.JSONObject
     * @throws HttpException
     */
    public JSONObject asJSONObject() throws ResponseException {
        try {
            return new JSONObject(asString());
        } catch (JSONException jsone) {
            throw new ResponseException(jsone.getMessage() + ":" + this.responseAsString, jsone);
        }
    }
    
    /**
     * Returns the response body as sinat4j.org.json.JSONArray.<br>
     * Disconnects the internal HttpURLConnection silently.
     * @return response body as sinat4j.org.json.JSONArray
     * @throws HttpException
     */
    public JSONArray asJSONArray() throws ResponseException {
        try {
        	return  new JSONArray(asString());  
        } catch (Exception jsone) {
            throw new ResponseException(jsone.getMessage() + ":" + this.responseAsString, jsone);
        }
    }

    public InputStreamReader asReader() {
        try {
            return new InputStreamReader(is, "UTF-8");
        } catch (java.io.UnsupportedEncodingException uee) {
            return new InputStreamReader(is);
        }
    }

    public void disconnect(){
        con.disconnect();
    }

    private static Pattern escaped = Pattern.compile("&#([0-9]{3,5});");

    /**
     * Unescape UTF-8 escaped characters to string.
     * @author pengjianq...@gmail.com
     *
     * @param original The string to be unescaped.
     * @return The unescaped string
     */
    public static String unescape(String original) {
        Matcher mm = escaped.matcher(original);
        StringBuffer unescaped = new StringBuffer();
        while (mm.find()) {
            mm.appendReplacement(unescaped, Character.toString(
                    (char) Integer.parseInt(mm.group(1), 10)));
        }
        mm.appendTail(unescaped);
        return unescaped.toString();
    }

    @Override
	public String toString() {
		return "Response [statusCode=" + statusCode + ", responseAsDocument="
				+ responseAsDocument + ", responseAsString=" + responseAsString
				+ ", is=" + is + ", con=" + con + ", streamConsumed="
				+ streamConsumed + ", response=" + response + ", statusLine="
				+ statusLine + ", responseHeader="
				+ Arrays.toString(responseHeader) + "]";
	}
	private void log(String message) {
        if (DEBUG) {
//            System.out.println("[" + new java.util.Date() + "]" + message);
        	Log.d(TAG, message);
        }
    }

    private void log(String message, String message2) {
        if (DEBUG) {
            log(message + message2);
        }
    }

	public String getResponseAsString() {
		return responseAsString;
	}

	public void setResponseAsString(String responseAsString) {
		this.responseAsString = responseAsString;
	}

	public void setStatusCode(int statusCode) {
		this.statusCode = statusCode;
	}
    
}
