package hex.genmodel.algos;

import deepwater.backends.BackendModel;
import deepwater.backends.BackendParams;
import deepwater.backends.BackendTrain;
import deepwater.backends.RuntimeOptions;
import deepwater.datasets.ImageDataSet;
import hex.genmodel.GenModel;
import hex.genmodel.MojoModel;

import java.io.IOException;
import java.util.Map;

public class DeepWaterMojo extends MojoModel {
  int _mini_batch_size;
  int _height;
  int _width;
  int _channels;
  protected byte[] _network;
  protected byte[] _parameters;

  BackendTrain _backend; //interface provider
  BackendModel _model;  //pointer to C++ process

  ImageDataSet _imageDataSet; //interface provider
  RuntimeOptions _opts;
  BackendParams _backendParams;

  public DeepWaterMojo(MojoReader cr, Map<String, Object> info, String[] columns, String[][] domains) {
    super(cr, info, columns, domains);
    try {
      _network = _reader.getBinaryFile("network");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    try {
      _parameters = _reader.getBinaryFile("parameters");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    _backend = GenModel.createDeepWaterBackend((String)info.get("backend")); // new ImageTrain(_width, _height, _channels, _deviceID, (int)parameters.getOrMakeRealSeed(), _gpu);
    _mini_batch_size = (int)info.get("mini_batch_size");
    _height = (int)info.get("height");
    _width = (int)info.get("width");
    _channels = (int)info.get("channels");

    _imageDataSet = new ImageDataSet(_width, _height, _channels);
    float[] meanData = _backend.loadMeanImage(_model, (String)info.get("mean_image_file"));
    if(meanData.length > 0) {
      _imageDataSet.setMeanData(meanData);
    }

    _opts = new RuntimeOptions();
    _opts.setSeed(0); // ignored
    _opts.setUseGPU(false); // don't use a GPU for inference
    _opts.setDeviceID(0); // ignored

    _backendParams = new BackendParams();
    _backendParams.set("mini_batch_size", _mini_batch_size);

    _model = _backend.buildNet(_imageDataSet, _opts, _backendParams, _nclasses, "model.network");
    _backend.loadParam(_model, "model.params");
  }

  /**
   * Corresponds to `hex.DeepWater.score0()`
   */
  @Override
  public final double[] score0(double[] row, double offset, double[] preds) {
    //allocate a big enough array for the model to be able to score with mini_batch
    float[] f = new float[_mini_batch_size * _channels * _height * _width];
    for (int i=0; i<row.length; ++i) f[i] = (float)row[i]; //only fill the first observation
    float[] predFloats = _backend.predict(_model, f);
    assert(_nclasses>=2) : "Only classification is supported right now.";
    double[] p = new double[_nclasses+1];
    for (int i=1; i<p.length; ++i) p[i] = predFloats[i];
    return p;
  }

  @Override
  public double[] score0(double[] row, double[] preds) {
    return score0(row, 0.0, preds);
  }

}
