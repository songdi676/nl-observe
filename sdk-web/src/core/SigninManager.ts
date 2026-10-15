/**
 * 签入状态机（详设 §1.5 core/SigninManager.ts）。
 *
 * 状态：SIGNED_OUT/SIGNED_IN。
 * sign_in 互斥：已签入再签入 → 先隐式 sign_out（console.warn）再签入；
 * sign_out 后 startBusiness 由 BusinessManager 依 isSignedIn 拒绝（warn+no-op）。
 */
export type SigninState = 'SIGNED_OUT' | 'SIGNED_IN';

export class SigninManager {
  private state: SigninState = 'SIGNED_OUT';

  /** 签入；已签入时先隐式 sign_out 并 warn（详设 §6.1 互斥语义；
   *  走 signOut() 而非直接改状态，未来 signOut 增副作用不会绕过——评审 n1）。 */
  signIn(): void {
    if (this.state === 'SIGNED_IN') {
      console.warn('[nl-observe] sign_in called while already signed in; implicit sign_out first');
      this.signOut();
    }
    this.state = 'SIGNED_IN';
  }

  /** 签出：后续 startBusiness 一律 no-op+warn（已建业务 span 不受影响）。 */
  signOut(): void {
    this.state = 'SIGNED_OUT';
  }

  isSignedIn(): boolean {
    return this.state === 'SIGNED_IN';
  }
}
