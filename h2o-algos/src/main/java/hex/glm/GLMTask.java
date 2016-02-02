package hex.glm;

import hex.DataInfo;
import hex.DataInfo.Row;

import hex.DataInfo.Rows;
import hex.FrameTask;
import hex.FrameTask2;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Link;
import hex.glm.GLMModel.GLMWeightsFun;
import hex.glm.GLMModel.GLMWeights;
import hex.gram.Gram;
import hex.glm.GLMModel.GLMParameters.Family;
import jsr166y.CountedCompleter;
import water.H2O.H2OCountedCompleter;
import water.*;
import water.fvec.*;
import water.util.ArrayUtils;
import water.util.MathUtils;
import water.util.MathUtils.BasicStats;

import java.util.Arrays;

/**
 * All GLM related distributed tasks:
 *
 * YMUTask           - computes response means on actual datasets (if some rows are ignored - e.g ignoring rows with NA and/or doing cross-validation)
 * GLMGradientTask   - computes gradient at given Beta, used by L-BFGS, for KKT condition check
 * GLMLineSearchTask - computes residual deviance(s) at given beta(s), used by line search (both L-BFGS and IRLSM)
 * GLMIterationTask  - used by IRLSM to compute Gram matrix and response t(X) W X, t(X)Wz
 *
 * @author tomasnykodym
 */
public abstract class GLMTask  {
  static class NullDevTask extends MRTask<NullDevTask> {
    double _nullDev;
    final double [] _ymu;
    final GLMWeightsFun _glmf;
    final boolean _hasWeights;
    final boolean _hasOffset;
    public NullDevTask(GLMWeightsFun glmf, double [] ymu, boolean hasWeights, boolean hasOffset) {
      _glmf = glmf;
      _ymu = ymu;
      _hasWeights = hasWeights;
      _hasOffset = hasOffset;
    }

    @Override public void map(Chunk [] chks) {
      int i = 0;
      int len = chks[0]._len;
      Chunk w = _hasWeights?chks[i++]:new C0DChunk(1.0,len);
      Chunk o = _hasOffset?chks[i++]:new C0DChunk(0.0,len);
      Chunk r = chks[i];
      if(_glmf._family != Family.multinomial) {
        double ymu = _glmf.link(_ymu[0]);
        for (int j = 0; j < len; ++j)
          _nullDev += w.atd(j)*_glmf.deviance(r.atd(j), _glmf.linkInv(ymu + o.atd(j)));
      } else {
        throw H2O.unimpl();
      }
    }
    @Override public void reduce(NullDevTask ndt) {_nullDev += ndt._nullDev;}
  }

  static class GLMResDevTask extends FrameTask2<GLMResDevTask> {
    final GLMWeightsFun _glmf;
    final double [] _beta;
    double _resDev = 0;
    double _likelihood;

    public GLMResDevTask(Key jobKey, DataInfo dinfo,GLMParameters parms, double [] beta) {
      super(null,dinfo, jobKey);
      _glmf = new GLMWeightsFun(parms);
      _beta = beta;
      _sparseOffset = _sparse?GLM.sparseOffset(_beta,_dinfo):0;
    }
    private transient GLMWeights _glmw;
    private final double _sparseOffset;

    @Override public boolean handlesSparseData(){return true;}
    @Override
    public void chunkInit() {
      _glmw = new GLMWeights();
    }
    @Override
    protected void processRow(Row r) {
      _glmf.computeWeights(r.response(0),r.innerProduct(_beta) + _sparseOffset,r.offset,r.weight,_glmw);
      _resDev += _glmw.dev;
      _likelihood += _glmw.l;
    }
    @Override public void reduce(GLMResDevTask gt) {_resDev += gt._resDev; _likelihood += gt._likelihood;}
  }

  static class GLMResDevTaskMultinomial extends FrameTask2<GLMResDevTaskMultinomial> {
    final double [][] _beta;
    double _likelihood;
    final int _nclasses;

    public GLMResDevTaskMultinomial(Key jobKey, DataInfo dinfo, double [] beta, int nclasses) {
      super(null,dinfo, jobKey);
      _beta = ArrayUtils.convertTo2DMatrix(beta,beta.length/nclasses);
      _nclasses = nclasses;
    }

    @Override public boolean handlesSparseData(){return true;}
    private transient double [] _sparseOffsets;

    @Override
    public void chunkInit() {
      _sparseOffsets = MemoryManager.malloc8d(_nclasses);
      for(int c = 0; c < _nclasses; ++c)
        _sparseOffsets[c] = GLM.sparseOffset(_beta[c],_dinfo);
    }
    @Override
    protected void processRow(Row r) {
      double sumExp = 0;
      for(int c = 0; c < _nclasses; ++c)
        sumExp += Math.exp(r.innerProduct(_beta[c]) + _sparseOffsets[c]);
      int c = (int)r.response(0);
      _likelihood -= r.weight * ((r.innerProduct(_beta[c]) + _sparseOffsets[c]) - Math.log(sumExp));
    }
    @Override public void reduce(GLMResDevTaskMultinomial gt) {_likelihood += gt._likelihood;}
  }

 static class YMUTask extends MRTask<YMUTask> {
   double _yMin = Double.POSITIVE_INFINITY, _yMax = Double.NEGATIVE_INFINITY;
   long _nobs;
   double _wsum;
   final int _responseId;
   final int _weightId;
   final int _offsetId;
   final int _nums; // number of numeric columns
   final int _numOff;
   final boolean _setIgnores;
   final boolean _comupteWeightedSigma;
   final boolean _skipNAs;

   BasicStats _basicStats;
   double [] _yMu;
   double [] _means;
   final int _nClasses;


   public YMUTask(DataInfo dinfo, int nclasses, boolean computeWeightedSigma, boolean setIgnores, boolean skipNAs){
     _nums = dinfo._nums;
     _numOff = dinfo._cats;
     _responseId = dinfo.responseChunkId(0);
     _weightId = dinfo._weights?dinfo.weightChunkId():-1;
     _offsetId = dinfo._offset?dinfo.offsetChunkId():-1;
     _nClasses = nclasses;
     _comupteWeightedSigma = computeWeightedSigma;
     _setIgnores = setIgnores;
     _skipNAs = skipNAs;
     _means = dinfo._numMeans;
   }
   @Override public void setupLocal(){}

   @Override public void map(Chunk [] chunks) {
     _yMu = new double[_nClasses];
     Chunk weight = _weightId == -1?new C0DChunk(1.0,chunks[0]._len):chunks[_weightId];
     boolean [] skip = MemoryManager.mallocZ(chunks[0]._len);
     for(int i = 0; i < chunks.length; ++i) {
       for (int r = chunks[i].nextNZ(-1); r < chunks[i]._len; r = chunks[i].nextNZ(r)) {
         if(skip[r])continue;
         if((skip[r] = _skipNAs && chunks[i].isNA(r)) && _setIgnores)
          weight.set(r,0);
       }
     }
     Chunk response = chunks[_responseId];
     double [] nums = null;
     if(_comupteWeightedSigma) {
       _basicStats = new BasicStats(_nums);
       nums = MemoryManager.malloc8d(_nums);
     }
     double w;
     for(int r = 0; r < response._len; ++r) {
       if(skip[r] || (w = weight.atd(r)) == 0)
         continue;
       if(_comupteWeightedSigma) {
         for(int i = 0; i < _nums; ++i) {
           nums[i] = chunks[i + _numOff].atd(r);
           if(Double.isNaN(nums[i]))
             nums[i] = _means[i];
         }
         _basicStats.add(nums,w);
       }
       double d = w*response.atd(r);
       if(!Double.isNaN(d)) {
         assert !Double.isNaN(d);
         if (_nClasses > 2)
           _yMu[(int) d] += 1;
         else
           _yMu[0] += d;
         if (d < _yMin)
           _yMin = d;
         if (d > _yMax)
           _yMax = d;
         _nobs++;
         _wsum += w;
       }
     }
   }
   @Override public void postGlobal() {
     ArrayUtils.mult(_yMu,1.0/_wsum);
     Futures fs = new Futures();
     fs.blockForPending();
   }
   @Override public void reduce(YMUTask ymt) {
     if(_nobs > 0 && ymt._nobs > 0) {
       ArrayUtils.add(_yMu,ymt._yMu);
       _nobs += ymt._nobs;
       _wsum += ymt._wsum;
       if(_yMin > ymt._yMin)
         _yMin = ymt._yMin;
       if(_yMax < ymt._yMax)
         _yMax = ymt._yMax;
       if(_comupteWeightedSigma) {
         _basicStats.reduce(ymt._basicStats);
       }
     } else if (_nobs == 0) {
       _yMu = ymt._yMu;
       _nobs = ymt._nobs;
       _yMin = ymt._yMin;
       _yMax = ymt._yMax;
       _basicStats = ymt._basicStats;
     }
   }
 }

  static double  computeMultinomialEtas(Row row, double [][]beta, final double [] etas, double [] etaOffsets, double [] exps) {
    double maxRow = 0;
    for (int c = 0; c < beta.length; ++c) {
      double e = etaOffsets[c] + row.innerProduct(beta[c]);
      if (e > maxRow) maxRow = e;
      etas[c] = e;
    }
    double sumExp = 0;
    for(int c = 0; c < beta.length; ++c) {
      double x = Math.exp(etas[c] - maxRow);
      sumExp += x;
      exps[c+1] = x;
    }
    double reg = 1.0/(sumExp);
    for(int c = 0; c < etas.length; ++c)
      exps[c+1] *= reg;
    exps[0] = 0;
    exps[0] = ArrayUtils.maxIndex(exps)-1;
    return Math.log(sumExp) + maxRow;
  }



  static class GLMGenericWeightsTask extends  FrameTask2<GLMGenericWeightsTask> {

    final double [] _beta;
    double _sparseOffset;

    private final GLMWeightsFun _glmw;
    private transient GLMWeights _ws;

    double _likelihood;

    public GLMGenericWeightsTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, double [] beta, GLMWeightsFun glmw) {
      super(cmp, dinfo, jobKey);
      _beta = beta;
      _glmw = glmw;
      assert _glmw._family != Family.multinomial:"Generic glm weights task does not work for family multinomial";
    }

    @Override public void chunkInit(){
      _ws = new GLMWeights();
      if(_sparse) _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
    }
    @Override
    protected void processRow(Row row) {
      double eta = row.innerProduct(_beta) + _sparseOffset;
      _glmw.computeWeights(row.response(0),eta,row.offset,row.weight,_ws);
      row.setOutput(0,_ws.w);
      row.setOutput(1,_ws.z);
      _likelihood += _ws.l;
    }

    @Override public void reduce(GLMGenericWeightsTask gwt) {
      _likelihood += gwt._likelihood;
    }
  }

  static class GLMMultinomialWeightsTask extends  FrameTask2<GLMGenericWeightsTask> {

    final double [] _beta;
    double _sparseOffset;

    double _likelihood;
    final int classId;

    public GLMMultinomialWeightsTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, double [] beta, int cid) {
      super(cmp, dinfo, jobKey);
      _beta = beta;
      classId = cid;
    }

    @Override public void chunkInit(){
      if(_sparse) _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
    }
    @Override
    protected void processRow(Row row) {
      double y = row.response(0);
      double maxRow = row.getOutput(2);
      double etaY = row.getOutput(3);
      double eta = row.innerProduct(_beta) + _sparseOffset;
      if(classId == y){
        etaY = eta;
        row.setOutput(3,eta);
      }
      if(eta > maxRow) {
        maxRow = eta;
        row.setOutput(2,eta);
      }
      double etaExp = Math.exp(eta - maxRow);
      double sumExp = row.getOutput(4) + etaExp;
      double mu = etaExp/sumExp;
      if(mu < 1e-16) mu = 1e-16;
      double d = mu*(1-mu);
      row.setOutput(0,row.weight * d);
      row.setOutput(1,eta + (y-mu)/d); // wz = r.weight * (eta * d + (y-mu));
      _likelihood += row.weight * (etaY - Math.log(sumExp) - maxRow);
    }

    @Override public void reduce(GLMGenericWeightsTask gwt) {
      _likelihood += gwt._likelihood;
    }
  }

  static class GLMBinomialWeightsTask extends  FrameTask2<GLMGenericWeightsTask> {
    final double [] _beta;
    double _sparseOffset;
    double _likelihood;

    public GLMBinomialWeightsTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, double [] beta) {
      super(cmp, dinfo, jobKey);
      _beta = beta;
    }

    @Override public void chunkInit(){
      if(_sparse) _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
    }
    @Override
    protected void processRow(Row row) {
      double y = row.response(0);
      double eta = row.innerProduct(_beta) + _sparseOffset;
      double mu = 1/(Math.exp(-eta) + 1);
      if(mu < 1e-16) mu = 1e-16;
      double d = mu*(1-mu);
      row.setOutput(0,row.weight * d);
      row.setOutput(1,eta + (y-mu)/d); // wz = r.weight * (eta * d + (y-mu));
      _likelihood += row.weight * (MathUtils.y_log_y(y, mu) + MathUtils.y_log_y(1 - y, 1 - mu));
    }

    @Override public void reduce(GLMGenericWeightsTask gwt) {
      _likelihood += gwt._likelihood;
    }
  }
  static abstract class GLMGradientTask extends FrameTask2<GLMGradientTask> {
    final double [] _beta;
    public double [] _gradient;
    public double _likelihood;
    final transient  double _currentLambda;
    final transient double _reg;

    protected GLMGradientTask(Key jobKey, DataInfo dinfo, double reg, double lambda, double[] beta){
      super(null,dinfo, jobKey);
      _beta = beta;
      _reg = reg;
      _currentLambda = lambda;
    }
    @Override public boolean handlesSparseData(){return true;}
    protected transient double _sparseOffset;
    @Override public final void chunkInit(){
      int icptId = _dinfo.fullN();
      _gradient = MemoryManager.malloc8d(icptId+1);
      if(_sparse)
        _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
    }

    @Override
    public final void reduce(GLMGradientTask gmgt){
      ArrayUtils.add(_gradient,gmgt._gradient);
      _likelihood += gmgt._likelihood;
    }
    @Override public final void postGlobal(){
      if(_sparse && _dinfo._normSub != null) {
        int numStart = _dinfo.numStart();
        for(int i = 0; i < _dinfo._normSub.length; ++i) {
          double d = _dinfo._normSub[i]*_dinfo._normMul[i];
          _gradient[numStart+i] -= d*_gradient[_gradient.length-1];
        }
      }
      ArrayUtils.mult(_gradient,_reg);
      for(int j = 0; j < _beta.length - 1; ++j)
        _gradient[j] += _currentLambda * _beta[j];
    }
  }

  static class GLMGenericGradientTask extends GLMGradientTask {
    private final GLMWeightsFun _glmf;
    public GLMGenericGradientTask(Key jobKey, DataInfo dinfo, GLMParameters parms, double lambda, double[] beta) {
      super(jobKey, dinfo, parms._obj_reg, lambda, beta);
      _glmf = new GLMWeightsFun(parms);
    }
    @Override protected void processRow(Row row) {
      double eta = row.innerProduct(_beta) + _sparseOffset + row.offset;
      double mu = _glmf.linkInv(eta);
      double [] g = _gradient;
      _likelihood += row.weight * _glmf.likelihood(row.response(0), mu);
      double var = _glmf.variance(mu);
      if (var < 1e-6) var = 1e-6; // to avoid numerical problems with 0 variance
      double gval = row.weight * (mu - row.response(0)) / (var * _glmf.linkDeriv(mu));
      // categoricals
      for (int i = 0; i < row.nBins; ++i)
        g[row.binIds[i]] += gval;
      int off = _dinfo.numStart();
      // numbers
      for (int j = 0; j < _dinfo._nums; ++j)
        g[j + off] += row.numVals[j] * gval;
      // intercept
      if (_dinfo._intercept)
        g[g.length - 1] += gval;
    }
  }


  static class GLMBinomialGradientTask extends GLMGradientTask {
    public GLMBinomialGradientTask(Key jobKey, DataInfo dinfo, GLMParameters parms, double lambda, double [] beta) {
      super(jobKey,dinfo,parms._obj_reg,lambda,beta);
      assert parms._family == Family.binomial && parms._link == Link.logit;
    }
    @Override
    protected void processRow(Row row) {
      double [] g = _gradient;
      double [] b = _beta;
      double y = -1 + 2*row.response(0);
      double eta = row.innerProduct(b) + _sparseOffset + row.offset;
      double gval;
      double d = 1 + Math.exp(-y * eta);
      _likelihood += row.weight*Math.log(d);
      gval = row.weight*-y*(1-1.0/d);
      // categoricals
      for(int i = 0; i < row.nBins; ++i)
        g[row.binIds[i]] += gval;
      int off = _dinfo.numStart();
      // numbers
      for (int j = 0; j < row.nNums; ++j)
        _gradient[(row.numIds == null ? j + off : row.numIds[j])] += row.numVals[j] * gval;
      // intercept
      g[g.length-1] += gval;
    }
  }

  static class GLMGaussianGradientTask extends GLMGradientTask {
    public GLMGaussianGradientTask(Key jobKey, DataInfo dinfo, GLMParameters parms, double lambda, double [] beta) {
      super(jobKey,dinfo,parms._obj_reg,lambda,beta);
      assert parms._family == Family.gaussian && parms._link == Link.identity;
    }
    @Override
    protected void processRow(Row row) {
      double [] g = _gradient;
      double [] b = _beta;
      double y = row.response(0);
      double eta = row.innerProduct(b) + _sparseOffset + row.offset;
      double diff = y - eta;
      _likelihood += row.weight*diff*diff;
      double gval = row.weight*(eta-y);
      // categoricals
      for(int i = 0; i < row.nBins; ++i)
        g[row.binIds[i]] += gval;
      int off = _dinfo.numStart();
      // numbers
      for (int j = 0; j < row.nNums; ++j)
        _gradient[(row.numIds == null ? j + off : row.numIds[j])] += row.numVals[j] * gval;
      // intercept
      g[g.length-1] += gval;
    }
  }

  static class GLMMultinomialGradientTask extends FrameTask2<GLMMultinomialGradientTask> {
    final double [][] _beta;
    final transient double _currentLambda;
    final transient double _reg;
    double [] _gradient;
    double _likelihood;

    public GLMMultinomialGradientTask(Key jobKey, DataInfo dinfo, double lambda, double[][] beta, double reg,boolean validate, H2OCountedCompleter cmp) {
      super(null,dinfo,jobKey);
      _currentLambda = lambda;
      _reg = reg;
      _beta = beta;
    }

    private transient double [] _etas;
    private transient double [] _exps;
    private transient double [] _etaOffsets;

    @Override public void chunkInit(){
      _gradient = new double[_beta.length*_beta[0].length];
      _etas = new double[_beta.length];
      _exps = new double[_beta.length+1];
      _etaOffsets = new double[_beta.length];
      if(_sparse)
        for(int i = 0; i < _etaOffsets.length; ++i)
          _etaOffsets[i] = GLM.sparseOffset(_beta[i],_dinfo);
    }
    @Override
    protected final void processRow(Row row) {
      int y = (int)row.response(0);
      assert y == row.response(0);
      double logSumExp = computeMultinomialEtas(row, _beta, _etas, _etaOffsets, _exps);
      final int P = _beta[0].length;
      _likelihood -= row.weight * (_etas[(int)row.response(0)] - logSumExp);
      int numOff = _dinfo.numStart();
      for(int c = 0; c < _beta.length; ++c) {
        double val = row.weight * (_exps[c+1] - (y == c?1:0));
        for (int j = 0; j < row.nBins; ++j)
          _gradient[c*P + row.binIds[j]] += val;
        for (int j = 0; j < row.nNums; ++j)
          _gradient[c*P + (row.numIds == null ? j + numOff : row.numIds[j])] += row.numVals[j] * val;
        _gradient[(c+1) * P - 1] += val;
      }
    }
    @Override
    public void reduce(GLMMultinomialGradientTask gmgt){
      ArrayUtils.add(_gradient,gmgt._gradient);
      _likelihood += gmgt._likelihood;
    }

    @Override public void postGlobal(){
      if (_sparse && _dinfo._normSub != null) { // adjust for centering
        int off = _dinfo.numStart();
        final int P = _beta[0].length;
        for(int c = 0; c < _beta.length; ++c) {
          double val = _gradient[(c+1)*P-1];
          for (int i = 0; i < _dinfo._nums; ++i)
            _gradient[c * P + off + i] -= val * _dinfo._normSub[i] * _dinfo._normMul[i];
        }
      }
      ArrayUtils.mult(_gradient, _reg);
      int P = _beta[0].length;
      for(int c = 0; c < _beta.length; ++c)
        for(int j = 0; j < P-1; ++j)
          _gradient[c*P+j] += _currentLambda * _beta[c][j];
    }
  }


//  public static class GLMCoordinateDescentTask extends MRTask<GLMCoordinateDescentTask> {
//    final double [] _betaUpdate;
//    final double [] _beta;
//    final double _xOldSub;
//    final double _xOldMul;
//    final double _xNewSub;
//    final double _xNewMul;
//
//    double [] _xy;
//
//    public GLMCoordinateDescentTask(double [] betaUpdate, double [] beta, double xOldSub, double xOldMul, double xNewSub, double xNewMul) {
//      _betaUpdate = betaUpdate;
//      _beta = beta;
//      _xOldSub = xOldSub;
//      _xOldMul = xOldMul;
//      _xNewSub = xNewSub;
//      _xNewMul = xNewMul;
//    }
//
//    public void map(Chunk [] chks) {
//      Chunk xOld = chks[0];
//      Chunk xNew = chks[1];
//      if(xNew.vec().isCategorical()){
//        _xy = MemoryManager.malloc8d(xNew.vec().domain().length);
//      } else
//      _xy = new double[1];
//      Chunk eta = chks[2];
//      Chunk weights = chks[3];
//      Chunk res = chks[4];
//      for(int i = 0; i < eta._len; ++i) {
//        double w = weights.atd(i);
//        double e = eta.atd(i);
//        if(_betaUpdate != null) {
//          if (xOld.vec().isCategorical()) {
//            int cid = (int) xOld.at8(i);
//            e = +_betaUpdate[cid];
//          } else
//            e += _betaUpdate[0] * (xOld.atd(i) - _xOldSub) * _xOldMul;
//          eta.set(i, e);
//        }
//        int cid = 0;
//        double x = w;
//        if(xNew.vec().isCategorical()) {
//          cid = (int) xNew.at8(i);
//          e -= _beta[cid];
//        } else {
//          x = (xNew.atd(i) - _xNewSub) * _xNewMul;
//          e -= _beta[0] * x;
//          x *= w;
//        }
//        _xy[cid] += x * (res.atd(i) - e);
//      }
//    }
//    @Override public void reduce(GLMCoordinateDescentTask t) {
//      ArrayUtils.add(_xy, t._xy);
//    }
//  }


//  /**
//   * Compute initial solution for multinomial problem (Simple weighted LR with all weights = 1/4)
//   */
//  public static final class GLMMultinomialInitTsk extends MRTask<GLMMultinomialInitTsk>  {
//    double [] _mu;
//    DataInfo _dinfo;
//    Gram _gram;
//    double [][] _xy;
//
//    @Override public void map(Chunk [] chks) {
//      Rows rows = _dinfo.rows(chks);
//      _gram = new Gram(_dinfo);
//      _xy = new double[_mu.length][_dinfo.fullN()+1];
//      int numStart = _dinfo.numStart();
//      double [] ds = new double[_mu.length];
//      for(int i = 0; i < ds.length; ++i)
//        ds[i] = 1.0/(_mu[i] * (1-_mu[i]));
//      for(int i = 0; i < rows._nrows; ++i) {
//        Row r = rows.row(i);
//        double y = r.response(0);
//        _gram.addRow(r,.25);
//        for(int c = 0; c < _mu.length; ++c) {
//          double iY = y == c?1:0;
//          double z = (y-_mu[c]) * ds[i];
//          for(int j = 0; j < r.nBins; ++j)
//            _xy[c][r.binIds[j]] += z;
//          for(int j = 0; j < r.nNums; ++j){
//            int id = r.numIds == null?(j + numStart):r.numIds[j];
//            double val = r.numVals[j];
//            _xy[c][id] += z*val;
//          }
//        }
//      }
//    }
//    @Override public void reduce(){
//
//    }
//  }

  /**
   * Task to compute t(X) %*% W %*%  X and t(X) %*% W %*% y
   */
  public static class LSTask extends FrameTask2<LSTask> {
    public double[] _xy;
    public Gram _gram;
    final int numStart;

    public LSTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey) {
      super(cmp, dinfo, jobKey);
      numStart = _dinfo.numStart();
    }

    @Override
    public void chunkInit() {
      _gram = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats, true);
      _xy = MemoryManager.malloc8d(_dinfo.fullN() + 1);

    }

    @Override
    protected void processRow(Row r) {
      double wz = r.weight * (r.response(0) - r.offset);
      for (int i = 0; i < r.nBins; ++i) {
        _xy[r.binIds[i]] += wz;
      }
      for (int i = 0; i < r.nNums; ++i) {
        int id = r.numIds == null ? (i + numStart) : r.numIds[i];
        double val = r.numVals[i];
        _xy[id] += wz * val;
      }
      if (_dinfo._intercept)
        _xy[_xy.length - 1] += wz;
      _gram.addRow(r, r.weight);
    }

    @Override
    public void reduce(LSTask lst) {
      ArrayUtils.add(_xy, lst._xy);
      _gram.add(lst._gram);
    }

    @Override
    public void postGlobal() {
      if (_sparse && _dinfo._normSub != null) { // need to adjust gram for missing centering!
        int ns = _dinfo.numStart();
        int interceptIdx = _xy.length - 1;
        double[] interceptRow = _gram._xx[interceptIdx - _gram._diagN];
        double nobs = interceptRow[interceptRow.length - 1]; // weighted _nobs
        for (int i = ns; i < _dinfo.fullN(); ++i) {
          double iMean = _dinfo._normSub[i - ns] * _dinfo._normMul[i - ns];
          for (int j = 0; j < ns; ++j)
            _gram._xx[i - _gram._diagN][j] -= interceptRow[j] * iMean;
          for (int j = ns; j <= i; ++j) {
            double jMean = _dinfo._normSub[j - ns] * _dinfo._normMul[j - ns];
            _gram._xx[i - _gram._diagN][j] -= interceptRow[i] * jMean + interceptRow[j] * iMean - nobs * iMean * jMean;
          }
        }
        if (_dinfo._intercept) { // do the intercept row
          for (int j = ns; j < _dinfo.fullN(); ++j)
            interceptRow[j] -= nobs * _dinfo._normSub[j - ns] * _dinfo._normMul[j - ns];
        }
        // and the xy vec as well
        for (int i = ns; i < _dinfo.fullN(); ++i) {
          _xy[i] -= _xy[_xy.length - 1] * _dinfo._normSub[i - ns] * _dinfo._normMul[i - ns];
        }
      }
    }
  }

  public static class GLMWLSTask extends LSTask {
    final GLMWeightsFun _glmw;
    final double [] _beta;
    double _sparseOffset;

    public GLMWLSTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, GLMWeightsFun glmw, double [] beta) {
      super(cmp, dinfo, jobKey);
      _glmw = glmw;
      _beta = beta;
    }

    private transient GLMWeights _ws;

    @Override public void chunkInit(){
      super.chunkInit();
      _ws = new GLMWeights();
    }

    @Override
    public void processRow(Row r) {
      // update weights
      double eta = r.innerProduct(_beta) + _sparseOffset;
      _glmw.computeWeights(r.response(0),eta,r.weight,r.offset,_ws);
      r.weight = _ws.w;
      r.offset = 0; // handled offset here
      r.setResponse(0,_ws.z);
      super.processRow(r);
    }
  }

  public static class GLMMultinomialWLSTask extends LSTask {
    final GLMWeightsFun _glmw;
    final double [] _beta;
    double _sparseOffset;

    public GLMMultinomialWLSTask(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, GLMWeightsFun glmw, double [] beta) {
      super(cmp, dinfo, jobKey);
      _glmw = glmw;
      _beta = beta;
    }

    private transient GLMWeights _ws;

    @Override public void chunkInit(){
      super.chunkInit();
      _ws = new GLMWeights();
    }

    @Override
    public void processRow(Row r) {

      // update weights
      double eta = r.innerProduct(_beta) + _sparseOffset;
      _glmw.computeWeights(r.response(0),eta,r.weight,r.offset,_ws);
      r.weight = _ws.w;
      r.offset = 0; // handled offset here
      r.setResponse(0,_ws.z);
      super.processRow(r);
    }
  }


  public static class GLMIterationTaskMultinomial extends FrameTask2<GLMIterationTaskMultinomial> {
    final int _c;
    final double [] _beta; // current beta to compute update of predictors for the current class

    double [] _xy;
    Gram _gram;
    transient double _sparseOffset;

    public GLMIterationTaskMultinomial(DataInfo dinfo, Key jobKey, double [] beta, int c) {
      super(null, dinfo, jobKey);
      _beta = beta;
      _c = c;
    }

    @Override public void chunkInit(){
      // initialize
      _gram = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats,true);
      _xy = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept
      if(_sparse)
        _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
    }
    @Override
    protected void processRow(Row r) {
      double y = r.response(0);
      double sumExp = r.response(1);
      double maxRow = r.response(2);
      int numStart = _dinfo.numStart();
      y = (y == _c)?1:0;
      double eta = r.innerProduct(_beta) + _sparseOffset;
      if(eta > maxRow) maxRow = eta;
      double etaExp = Math.exp(eta-maxRow);
      sumExp += etaExp;
      double mu = (etaExp == Double.POSITIVE_INFINITY?1:(etaExp / sumExp));
      if(mu < 1e-16)
        mu = 1e-16;//
      double d = mu*(1-mu);
      double wz = r.weight * (eta * d + (y-mu));
      double w  = r.weight * d;
      for(int i = 0; i < r.nBins; ++i) {
        _xy[r.binIds[i]] += wz;
      }
      for(int i = 0; i < r.nNums; ++i){
        int id = r.numIds == null?(i + numStart):r.numIds[i];
        double val = r.numVals[i];
        _xy[id] += wz*val;
      }
      if(_dinfo._intercept)
        _xy[_xy.length-1] += wz;
      _gram.addRow(r, w);
    }

    @Override
    public void reduce(GLMIterationTaskMultinomial glmt) {
      ArrayUtils.add(_xy,glmt._xy);
      _gram.add(glmt._gram);
    }
  }

  public static class GLMMultinomialUpdate extends FrameTask2<GLMMultinomialUpdate> {
    private final double [][] _beta; // updated  value of beta
    private final int _c;
    private transient double [] _sparseOffsets;
    private transient double [] _etas;

    public GLMMultinomialUpdate(DataInfo dinfo, Key jobKey, double [] beta, int c) {
      super(null, dinfo, jobKey);
      _beta = ArrayUtils.convertTo2DMatrix(beta,dinfo.fullN()+1);
      _c = c;
    }

    @Override public void chunkInit(){
      // initialize
      _sparseOffsets = MemoryManager.malloc8d(_beta.length);
      _etas = MemoryManager.malloc8d(_beta.length);
      if(_sparse) {
        for(int i = 0; i < _beta.length; ++i)
          _sparseOffsets[i] = GLM.sparseOffset(_beta[i],_dinfo);
      }
    }

    private transient Chunk _sumExpChunk;
    private transient Chunk _maxRowChunk;

    @Override public void map(Chunk [] chks) {
      _sumExpChunk = chks[chks.length-2];
      _maxRowChunk = chks[chks.length-1];
      super.map(chks);
    }

    @Override
    protected void processRow(Row r) {
      double maxrow = 0;
      for(int i = 0; i < _beta.length; ++i) {
        _etas[i] = r.innerProduct(_beta[i]) + _sparseOffsets[i];
        if(_etas[i] > maxrow)
          maxrow = _etas[i];
      }
      double sumExp = 0;
      for(int i = 0; i < _beta.length; ++i)
//        if(i != _c)
          sumExp += Math.exp(_etas[i]-maxrow);
      _maxRowChunk.set(r.cid,_etas[_c]);
      _sumExpChunk.set(r.cid,Math.exp(_etas[_c]-maxrow)/sumExp);
    }
  }

  /**
   * One iteration of glm, computes weighted gram matrix and t(x)*y vector and t(y)*y scalar.
   *
   * @author tomasnykodym
   */
  public static class GLMIterationTask extends FrameTask2<GLMIterationTask> {
    final GLMWeightsFun _glmf;
    double [][]_beta_multinomial;
    double []_beta;
    protected Gram  _gram; // wx%*%x
    double [] _xy; // wx^t%*%z,

    final double [] _ymu;

    long _nobs;
    public double _likelihood;
    private transient GLMWeights _w;
//    final double _lambda;
    double wsum, wsumu;
    double _sumsqe;
    int _c = -1;

    public  GLMIterationTask(Key jobKey, DataInfo dinfo, GLMWeightsFun glmw,double [] beta) {
      super(null,dinfo,jobKey);
      _beta = beta;
      _ymu = null;
      _glmf = glmw;
    }

    public  GLMIterationTask(Key jobKey, DataInfo dinfo, GLMWeightsFun glmw, double [] beta, int c) {
      super(null,dinfo,jobKey);
      _beta = beta;
      _ymu = null;
      _glmf = glmw;
      _c = c;
    }

    @Override public boolean handlesSparseData(){return true;}

    transient private double _sparseOffset;
    @Override
    public void chunkInit() {
      // initialize
      _gram = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats,true);
      _xy = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept
      if(_sparse)
         _sparseOffset = GLM.sparseOffset(_beta,_dinfo);
      _w = new GLMWeights();
    }

    @Override
    protected void processRow(Row r) { // called for every row in the chunk
      if(r.bad || r.weight == 0) return;
      ++_nobs;
      double y = r.response(0);
      final int numStart = _dinfo.numStart();
      double wz,w;
      if(_glmf._family == Family.multinomial) {
//        double maxRow = r.response(2);
        y = (y == _c)?1:0;
//        double eta = r.innerProduct(_beta) + _sparseOffset;
//          double etaExp = Math.exp(eta-maxRow);
//        double sumExp = r.response(1);// + etaExp;
//        double mu = (etaExp == Double.POSITIVE_INFINITY?1:(etaExp / sumExp));
//        double mu = etaExp/sumExp;
        double mu = r.response(1);
        double eta = r.response(2);
        double d = mu*(1-mu);
        wz = r.weight * (eta * d + (y-mu));
        w  = r.weight * d;
      } else if(_beta != null) {
        _glmf.computeWeights(y, r.innerProduct(_beta) + _sparseOffset, r.offset, r.weight, _w);
        w = _w.w;
        wz = w*_w.z;
      } else {
        w = r.weight;
        wz = w*(y - r.offset);
      }
      wsum+=w;
      wsumu+=r.weight; // just add the user observation weight for the scaling.
      for(int i = 0; i < r.nBins; ++i)
        _xy[r.binIds[i]] += wz;
      for(int i = 0; i < r.nNums; ++i){
        int id = r.numIds == null?(i + numStart):r.numIds[i];
        double val = r.numVals[i];
        _xy[id] += wz*val;
      }
      if(_dinfo._intercept)
        _xy[_xy.length-1] += wz;
      _gram.addRow(r,w);
    }

    @Override
    public void reduce(GLMIterationTask git){
      ArrayUtils.add(_xy, git._xy);
      _gram.add(git._gram);
      _nobs += git._nobs;
      wsum += git.wsum;
      wsumu += git.wsumu;
      _likelihood += git._likelihood;
      _sumsqe += git._sumsqe;
      super.reduce(git);
    }

    @Override protected void postGlobal(){
      if(_sparse && _dinfo._normSub != null) { // need to adjust gram for missing centering!
        int ns = _dinfo.numStart();
        int interceptIdx = _xy.length - 1;
        double[] interceptRow = _gram._xx[interceptIdx - _gram._diagN];
        double nobs = interceptRow[interceptRow.length - 1]; // weighted _nobs
        for (int i = ns; i < _dinfo.fullN(); ++i) {
          double iMean = _dinfo._normSub[i - ns] * _dinfo._normMul[i - ns];
          for (int j = 0; j < ns; ++j)
            _gram._xx[i - _gram._diagN][j] -= interceptRow[j] * iMean;
          for (int j = ns; j <= i; ++j) {
            double jMean = _dinfo._normSub[j - ns] * _dinfo._normMul[j - ns];
            _gram._xx[i - _gram._diagN][j] -= interceptRow[i] * jMean + interceptRow[j] * iMean - nobs * iMean * jMean;
          }
        }
        if (_dinfo._intercept) { // do the intercept row
          for (int j = ns; j < _dinfo.fullN(); ++j)
            interceptRow[j] -= nobs * _dinfo._normSub[j - ns] * _dinfo._normMul[j - ns];
        }
        // and the xy vec as well
        for (int i = ns; i < _dinfo.fullN(); ++i) {
          _xy[i] -= _xy[_xy.length - 1] * _dinfo._normSub[i - ns] * _dinfo._normMul[i - ns];
        }
      }
    }
    public boolean hasNaNsOrInf() {
      return ArrayUtils.hasNaNsOrInfs(_xy) || _gram.hasNaNsOrInfs();
    }
  }

 /* public static class GLMCoordinateDescentTask extends FrameTask2<GLMCoordinateDescentTask> {
    final GLMParameters _params;
    final double [] _betaw;
    final double [] _betacd;
    public double [] _temp;
    public double [] _varsum;
    public double _ws=0;
    long _nobs;
    public double _likelihoods;
    public  GLMCoordinateDescentTask(Key jobKey, DataInfo dinfo, double lambda, GLMModel.GLMParameters glm, boolean validate, double [] betaw,
                                     double [] betacd, double ymu, Vec rowFilter, H2OCountedCompleter cmp) {
      super(cmp,dinfo,jobKey,rowFilter);
      _params = glm;
      _betaw = betaw;
      _betacd = betacd;
    }


    @Override public boolean handlesSparseData(){return false;}


    @Override
    public void chunkInit() {
      _temp=MemoryManager.malloc8d(_dinfo.fullN()+1); // using h2o memory manager
      _varsum=MemoryManager.malloc8d(_dinfo.fullN());
    }

    @Override
    protected void processRow(Row r) {
      if(r.bad || r.weight == 0) return;
      ++_nobs;
      final double y = r.response(0);
      assert ((_params._family != Family.gamma) || y > 0) : "illegal response column, y must be > 0  for family=Gamma.";
      assert ((_params._family != Family.binomial) || (0 <= y && y <= 1)) : "illegal response column, y must be <0,1>  for family=Binomial. got " + y;
      final double w, eta, mu, var, z;
      final int numStart = _dinfo.numStart();
      double d = 1;
      if( _params._family == Family.gaussian && _params._link == Link.identity){
        w = r.weight;
        z = y - r.offset;
        mu = 0;
        eta = mu;
      } else {
        eta = r.innerProduct(_betaw);
        mu = _params.linkInv(eta + r.offset);
        var = Math.max(1e-6, _params.variance(mu)); // avoid numerical problems with 0 variance
        d = _params.linkDeriv(mu);
        z = eta + (y-mu)*d;
        w = r.weight/(var*d*d);
      }
      _likelihoods += r.weight*_params.likelihood(y,mu);
      assert w >= 0|| Double.isNaN(w) : "invalid weight " + w; // allow NaNs - can occur if line-search is needed!

      _ws+=w;
      double xb = r.innerProduct(_betacd);
      for(int i = 0; i < r.nBins; ++i)  { // go over cat variables
        _temp[r.binIds[i]] += (z - xb + _betacd[r.binIds[i]])  *w;
        _varsum[r.binIds[i]] += w ;
      }
      for(int i = 0; i < r.nNums; ++i){ // num vars
        int id = r.numIds == null?(i + numStart):r.numIds[i];
        _temp[id] += (z- xb + r.get(id)*_betacd[id] )*(r.get(id)*w);
        _varsum[id] += w*r.get(id)*r.get(id);
      }
        _temp[_temp.length-1] += w*(z-r.innerProduct(_betacd)+_betacd[_betacd.length-1]);
    }

    @Override
    public void reduce(GLMCoordinateDescentTask git){ // adding contribution of all the chunks
      ArrayUtils.add(_temp, git._temp);
      ArrayUtils.add(_varsum, git._varsum);
      _ws+= git._ws;
      _nobs += git._nobs;
      _likelihoods += git._likelihoods;
      super.reduce(git);
    }

  }
*/

  public static class GLMCoordinateDescentTaskSeqNaive extends MRTask<GLMCoordinateDescentTaskSeqNaive> {
    public double [] _normMulold;
    public double [] _normSubold;
    public double [] _normMulnew;
    public double [] _normSubnew;
    final double [] _betaold; // current old value at j
    final double [] _betanew; // global beta @ j-1 that was just updated.
    final int [] _catLvls_new; // sorted list of indices of active levels only for one categorical variable
    final int [] _catLvls_old;
    public double [] _temp;
    boolean _skipFirst;
    long _nobs;
    int _cat_num; // 1: c and p categorical, 2:c numeric and p categorical, 3:c and p numeric , 4: c categorical and previous num.
    boolean _interceptnew;
    boolean _interceptold;

    public  GLMCoordinateDescentTaskSeqNaive(boolean interceptold, boolean interceptnew, int cat_num ,
                                        double [] betaold, double [] betanew, int [] catLvlsold, int [] catLvlsnew,
                                        double [] normMulold, double [] normSubold, double [] normMulnew, double [] normSubnew,
                                             boolean skipFirst ) { // pass it norm mul and norm sup - in the weights already done. norm
      //mul and mean will be null without standardization.
      _normMulold = normMulold;
      _normSubold = normSubold;
      _normMulnew = normMulnew;
      _normSubnew = normSubnew;
      _cat_num = cat_num;
      _betaold = betaold;
      _betanew = betanew;
      _interceptold = interceptold; // if updating beta_1, then the intercept is the previous column
      _interceptnew = interceptnew; // if currently updating the intercept value
      _catLvls_old = catLvlsold;
      _catLvls_new = catLvlsnew;
      _skipFirst = skipFirst;
    }

    @Override
    public void map(Chunk [] chunks) {
      int cnt = 0;
      Chunk wChunk = chunks[cnt++];
      Chunk zChunk = chunks[cnt++];
      Chunk ztildaChunk = chunks[cnt++];
      Chunk xpChunk=null, xChunk=null;

      _temp = new double[_betaold.length];
      if (_interceptnew) {
        xChunk = new C0DChunk(1,chunks[0]._len);
        xpChunk = chunks[cnt++];
      } else {
        if (_interceptold) {
          xChunk = chunks[cnt++];
          xpChunk = new C0DChunk(1,chunks[0]._len);
        }
        else {
          xChunk = chunks[cnt++];
          xpChunk = chunks[cnt++];
        }
      }

      // For each observation, add corresponding term to temp - or if categorical variable only add the term corresponding to its active level and the active level
      // of the most recently updated variable before it (if also cat). If for an obs the active level corresponds to an inactive column, we just dont want to include
      // it - same if inactive level in most recently updated var. so set these to zero ( Wont be updating a betaj which is inactive) .
      for (int i = 0; i < chunks[0]._len; ++i) { // going over all the rows in the chunk
        double betanew = 0; // most recently updated prev variable
        double betaold = 0; // old value of current variable being updated
        double w = wChunk.atd(i);
        if(w == 0) continue;
        ++_nobs;
        int observation_level = 0, observation_level_p = 0;
        double val = 1, valp = 1;
        if(_cat_num == 1) {
          observation_level = (int) xChunk.at8(i); // only need to change one temp value per observation.
          if (_catLvls_old != null)
            observation_level = Arrays.binarySearch(_catLvls_old, observation_level);

          observation_level_p = (int) xpChunk.at8(i); // both cat
          if (_catLvls_new != null)
            observation_level_p = Arrays.binarySearch(_catLvls_new, observation_level_p);

          if(_skipFirst){
            observation_level--;
            observation_level_p--;
          }
        }
        else if(_cat_num == 2){
          val = xChunk.atd(i); // current num and previous cat
          if (_normMulold != null && _normSubold != null)
            val = (val - _normSubold[0]) * _normMulold[0];

          observation_level_p = (int) xpChunk.at8(i);
          if (_catLvls_new != null)
            observation_level_p = Arrays.binarySearch(_catLvls_new, observation_level_p);

          if(_skipFirst){
            observation_level_p--;
          }
        }
        else if(_cat_num == 3){
          val = xChunk.atd(i); // both num
          if (_normMulold != null && _normSubold != null)
            val = (val - _normSubold[0]) * _normMulold[0];
          valp = xpChunk.atd(i);
          if (_normMulnew != null && _normSubnew != null)
            valp = (valp - _normSubnew[0]) * _normMulnew[0];
        }
        else if(_cat_num == 4){
          observation_level = (int) xChunk.at8(i); // current cat
          if (_catLvls_old != null)
            observation_level = Arrays.binarySearch(_catLvls_old, observation_level); // search to see if this level is active.
          if(_skipFirst){
            observation_level--;
          }

          valp = xpChunk.atd(i); //prev numeric
          if (_normMulnew != null && _normSubnew != null)
            valp = (valp - _normSubnew[0]) * _normMulnew[0];
        }

        if(observation_level >= 0)
         betaold = _betaold[observation_level];
        if(observation_level_p >= 0)
         betanew = _betanew[observation_level_p];

        if (_interceptnew) {
            ztildaChunk.set(i, ztildaChunk.atd(i) - betaold + valp * betanew); //
            _temp[0] += w * (zChunk.atd(i) - ztildaChunk.atd(i));
          } else {
            ztildaChunk.set(i, ztildaChunk.atd(i) - val * betaold + valp * betanew);
            if(observation_level >=0 ) // if the active level for that observation is an "inactive column" don't want to add contribution to temp for that observation
            _temp[observation_level] += w * val * (zChunk.atd(i) - ztildaChunk.atd(i));
         }

       }

    }

    @Override
    public void reduce(GLMCoordinateDescentTaskSeqNaive git){
      ArrayUtils.add(_temp, git._temp);
      _nobs += git._nobs;
      super.reduce(git);
    }

  }


  public static class GLMCoordinateDescentTaskSeqIntercept extends MRTask<GLMCoordinateDescentTaskSeqIntercept> {
    final double [] _betaold;
    public double _temp;
    DataInfo _dinfo;

    public  GLMCoordinateDescentTaskSeqIntercept( double [] betaold, DataInfo dinfo) {
      _betaold = betaold;
      _dinfo = dinfo;
    }

    @Override
    public void map(Chunk [] chunks) {
      int cnt = 0;
      Chunk wChunk = chunks[cnt++];
      Chunk zChunk = chunks[cnt++];
      Chunk filterChunk = chunks[cnt++];
      Row r = _dinfo.newDenseRow();
      for(int i = 0; i < chunks[0]._len; ++i) {
        if(filterChunk.atd(i)==1) continue;
        _dinfo.extractDenseRow(chunks,i,r);
        _temp = wChunk.at8(i)* (zChunk.atd(i)- r.innerProduct(_betaold) );
      }

    }

    @Override
    public void reduce(GLMCoordinateDescentTaskSeqIntercept git){
      _temp+= git._temp;
      super.reduce(git);
    }

  }


  public static class GLMGenerateWeightsTask extends MRTask<GLMGenerateWeightsTask> {
    final GLMParameters _params;
    final double [] _betaw;
    double [] denums;
    double wsum,wsumu;
    DataInfo _dinfo;
    double _likelihood;

    public GLMGenerateWeightsTask(Key jobKey, DataInfo dinfo, GLMModel.GLMParameters glm, double[] betaw) {
      _params = glm;
      _betaw = betaw;
      _dinfo = dinfo;
    }

    @Override
    public void map(Chunk [] chunks) {
      Chunk wChunk = chunks[chunks.length-3];
      Chunk zChunk = chunks[chunks.length-2];
      Chunk zTilda = chunks[chunks.length-1];
      chunks = Arrays.copyOf(chunks,chunks.length-3);
      denums = new double[_dinfo.fullN()+1]; // full N is expanded variables with categories

      Row r = _dinfo.newDenseRow();
      for(int i = 0; i < chunks[0]._len; ++i) {
        _dinfo.extractDenseRow(chunks,i,r);
        if (r.bad || r.weight == 0) {
          wChunk.set(i,0);
          zChunk.set(i,0);
          zTilda.set(i,0);
          continue;
        }
        final double y = r.response(0);
        assert ((_params._family != Family.gamma) || y > 0) : "illegal response column, y must be > 0  for family=Gamma.";
        assert ((_params._family != Family.binomial) || (0 <= y && y <= 1)) : "illegal response column, y must be <0,1>  for family=Binomial. got " + y;
        final double w, eta, mu, var, z;
        final int numStart = _dinfo.numStart();
        double d = 1;
        eta = r.innerProduct(_betaw);
        if (_params._family == Family.gaussian && _params._link == Link.identity) {
          w = r.weight;
          z = y - r.offset;
          mu = 0;
        } else {
          mu = _params.linkInv(eta + r.offset);
          var = Math.max(1e-6, _params.variance(mu)); // avoid numerical problems with 0 variance
          d = _params.linkDeriv(mu);
          z = eta + (y - mu) * d;
          w = r.weight / (var * d * d);
        }
        _likelihood += _params.likelihood(y,mu);
        zTilda.set(i,eta-_betaw[_betaw.length-1]);
        assert w >= 0 || Double.isNaN(w) : "invalid weight " + w; // allow NaNs - can occur if line-search is needed!
        wChunk.set(i,w);
        zChunk.set(i,z);

        wsum+=w;
        wsumu+=r.weight; // just add the user observation weight for the scaling.

        for(int j = 0; j < r.nBins; ++j)  { // go over cat variables
          denums[r.binIds[j]] +=  w; // binIds skips the zeros.
        }
        for(int j = 0; j < r.nNums; ++j){ // num vars
          int id = r.numIds == null?(j + numStart):r.numIds[j];
          denums[id]+= w*r.get(id)*r.get(id);
        }

      }
    }

    @Override
    public void reduce(GLMGenerateWeightsTask git){ // adding contribution of all the chunks
      ArrayUtils.add(denums, git.denums);
      wsum+=git.wsum;
      wsumu += git.wsumu;
      _likelihood += git._likelihood;
      super.reduce(git);
    }


  }


  public static class ComputeSETsk extends FrameTask2<ComputeSETsk> {
//    final double [] _betaOld;
    final double [] _betaNew;
    double _sumsqe;
    double _wsum;

    public ComputeSETsk(H2OCountedCompleter cmp, DataInfo dinfo, Key jobKey, /*, double [] betaOld,*/ double [] betaNew, GLMParameters parms) {
      super(cmp, dinfo, jobKey);
//      _betaOld = betaOld;
      _glmf = new GLMWeightsFun(parms);
      _betaNew = betaNew;
    }

    transient double _sparseOffsetOld = 0;
    transient double _sparseOffsetNew = 0;
    final GLMWeightsFun _glmf;
    transient GLMWeights _glmw;
    @Override public void chunkInit(){
      if(_sparse) {
//        _sparseOffsetOld = GLM.sparseOffset(_betaNew, _dinfo);
        _sparseOffsetNew = GLM.sparseOffset(_betaNew, _dinfo);
      }
      _glmw = new GLMWeights();
    }

    @Override
    protected void processRow(Row r) {
      double z = r.response(0) - r.offset;
      double w = r.weight;
      if(_glmf._family != Family.gaussian) {
//        double etaOld = r.innerProduct(_betaOld) + _sparseOffsetOld;
        double etaOld = r.innerProduct(_betaNew) + _sparseOffsetNew;
        _glmf.computeWeights(r.response(0),etaOld,r.offset,r.weight,_glmw);
        z = _glmw.z;
        w = _glmw.w;
      }
      double eta = r.innerProduct(_betaNew) + _sparseOffsetNew;
//      double mu = _parms.linkInv(eta);
      _sumsqe += w*(eta - z)*(eta - z);
      _wsum += Math.sqrt(w);
    }
    @Override
    public void reduce(ComputeSETsk c){_sumsqe += c._sumsqe; _wsum += c._wsum;}
  }

}
