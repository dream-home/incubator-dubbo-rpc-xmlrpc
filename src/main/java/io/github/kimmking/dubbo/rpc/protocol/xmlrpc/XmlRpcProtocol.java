package io.github.kimmking.dubbo.rpc.protocol.xmlrpc;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.XmlRpcRequest;
import org.apache.xmlrpc.server.PropertyHandlerMapping;
import org.apache.xmlrpc.server.RequestProcessorFactoryFactory;
import org.apache.xmlrpc.server.XmlRpcServerConfigImpl;
import org.apache.xmlrpc.webserver.XmlRpcServletServer;
import org.springframework.remoting.RemoteAccessException;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.http.HttpBinder;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.HttpServer;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;

/**
 * Created by wuwen on 15/4/1.
 */
public class XmlRpcProtocol extends AbstractProxyProtocol {
    
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";

    private final Map<String, HttpServer> serverMap = new ConcurrentHashMap<>();

    private final Map<String, XmlRpcServletServer> skeletonMap = new ConcurrentHashMap<>();

    private HttpBinder httpBinder;

    public XmlRpcProtocol() {
        super(XmlRpcException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    public int getDefaultPort() {
        return 80;
    }

    private class InternalHandler implements HttpHandler {
    	
        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String uri = request.getRequestURI();
            XmlRpcServletServer xmlrpc = skeletonMap.get(uri);
            if (cors) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }
            if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
                response.setStatus(200);
            } else if (request.getMethod().equalsIgnoreCase("POST")) {

                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    xmlrpc.execute (request,response);
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            } else {
                response.setStatus(500);
            }
        }

    }

    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        final URL http_url = url.setProtocol("http");
        String addr = http_url.getIp() + ":" + http_url.getPort();
        HttpServer server = serverMap.get(addr);
        if (server == null) {
            server = httpBinder.bind(http_url, new InternalHandler(http_url.getParameter("cors", false)));
            serverMap.put(addr, server);
        }
        final String path = http_url.getAbsolutePath();

        XmlRpcServletServer xmlRpcServer = new XmlRpcServletServer();

        PropertyHandlerMapping propertyHandlerMapping = new PropertyHandlerMapping();
        try {

            propertyHandlerMapping.setRequestProcessorFactoryFactory(new RequestProcessorFactoryFactory(){
                public RequestProcessorFactory getRequestProcessorFactory(Class pClass) throws XmlRpcException{
                    return new RequestProcessorFactory(){
                        public Object getRequestProcessor(XmlRpcRequest pRequest) throws XmlRpcException{
                            return impl;
                        }
                    };
                }
            });

            propertyHandlerMapping.addHandler(XmlRpcProxyFactoryBean.replace(type.getName()),type);

        } catch (Exception e) {
            throw new RpcException(e);
        }
        xmlRpcServer.setHandlerMapping(propertyHandlerMapping);

        XmlRpcServerConfigImpl xmlRpcServerConfig = (XmlRpcServerConfigImpl) xmlRpcServer.getConfig();
        xmlRpcServerConfig.setEnabledForExceptions(true);
        xmlRpcServerConfig.setContentLengthOptional(false);

        skeletonMap.put(path, xmlRpcServer);
        return new Runnable() {
            public void run() {
                skeletonMap.remove(path);
            }
        };
    }

    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        XmlRpcProxyFactoryBean xmlRpcProxyFactoryBean = new XmlRpcProxyFactoryBean();
        xmlRpcProxyFactoryBean.setServiceUrl(url.setProtocol("http").toIdentityString());
        xmlRpcProxyFactoryBean.setServiceInterface(serviceType);
        xmlRpcProxyFactoryBean.afterPropertiesSet();
        return (T) xmlRpcProxyFactoryBean.getObject();
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            HttpServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close xmlrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }
}