import { NavLink } from "react-router";

export function Nav() {
  return (
    <nav className="nav">
      <span className="brand">🧹 unfuckdoc</span>
      <NavLink to="/" end>Explore</NavLink>
      <NavLink to="/collections">Collections</NavLink>
      <NavLink to="/match">Match</NavLink>
      <span className="sub">messy CSV → classify · clean · unify · search</span>
    </nav>
  );
}
