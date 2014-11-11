// This file is compiled into the jar and executed automatically on startup.



var __this__=this;

   
    

var require = (function(__this__) {
        var cached = {};
        var envjsGlobals={};

       var javaInstance=Packages.org.jdownloader.scripting.envjs.EnvJS.get(%EnvJSinstanceID%);
       javaInstance.setGlobals(envjsGlobals);
  
        var require= function(id) {
    
          
          
      
            if (!cached.hasOwnProperty(id)) {
                print('require :'+ id);
                var source = ""+javaInstance.readRequire(id);
          
                source = source.replace(/^\#\!.*/, '');
                source = (
                    "(function (envjsGlobals,require, exports, module,javaInstance,__this__) { " + source + "\n});");
                cached[id] = {
                    exports: __this__,
                    module: {
                        id: id,
                        uri: id
                    }
                };
   
                
                try {
     
                    var ctx = net.sourceforge.htmlunit.corejs.javascript.Context.getCurrentContext();
                    var func = ctx.evaluateString({}, source, id, 1, null);
                 
                    func(envjsGlobals,require, cached[id].exports, cached[id].module,javaInstance,__this__);
                } finally {
                   
                }
            }
    		/*
             * print('returning exports for id: '+id+' '+cached[id].exports);
             * for(var prop in cached[id].exports){ print('export: '+prop); }
             */
            return cached[id].exports;
        };
        
  
        
        return require;
    }(this));


require('envjs/platform/core');

require('local_settings');
require('envjs/platform/rhino');
require('envjs/window');

//init window
new Window(this);
delete __this__;
delete require;


