package com.example.infrastructure_service.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class VirtualSocket extends Socket {
    private InputStream in;
    private OutputStream out;

    @Override
    public InputStream getInputStream() {
            return in;
    }
        
    @Override
    public OutputStream getOutputStream() {
        return out;
    }
        
    @Override
    public boolean isConnected() {
        return true;
    }
        
    @Override
    public void close() throws IOException {
        if(in != null) in.close();
        if(out != null) out.close();
    }
    
}
